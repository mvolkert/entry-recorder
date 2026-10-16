package io.github.mvolkert.entryrecorder.sip

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mvolkert.entryrecorder.di.ApplicationScope
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.data.model.SipMode
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.linphone.core.*
import kotlin.time.Duration.Companion.milliseconds

enum class CallUiState {
    IDLE,
    RINGING_INCOMING,
    CONNECTED,
    ENDED,
    ERROR
}

data class SipSessionState(
    val state: CallUiState = CallUiState.IDLE,
    val isMicMuted: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val errorMessage: String? = null,
    // Linphone's own state (plus its reason text) verbatim, shown on the call screen in debug builds.
    val rawCallState: String? = null
)

/** Which input [SipCallManager.probeRegistration] needs before it can ask a registrar anything. */
enum class SipMissingField { CALLING_MODE, PBX_HOST, SIP_USER }

/**
 * Outcome of a one-shot registration attempt, delivered as data so the device form can show it instead of
 * making the user read logcat.
 */
sealed interface SipProbeResult {
    /** The registrar accepted the credentials. */
    data class Registered(val server: String) : SipProbeResult

    /** It answered no — wrong password, unknown user, refused transport. */
    data class Rejected(val state: String, val reason: String) : SipProbeResult

    /**
     * The registrar never answered inside [SipCallTiming.SIP_PROBE_TIMEOUT_MS]. [observed] is the last
     * registration state the probe core reported, null when it never left idle - which says nothing was sent,
     * i.e. a local transport problem rather than a silent server.
     */
    data class NoAnswer(val server: String, val observed: String?) : SipProbeResult

    /** Nothing was sent: the form is not ready to be tested yet. */
    data class MissingFields(val field: SipMissingField) : SipProbeResult
}

// The Linphone core lives for the whole process, so it is a graph singleton. Hilt hands out the
// application context here, which is what keeps this class from ever retaining an Activity or Service —
// the same reason the hand-rolled companion singleton used to insist on Application.
@Singleton
class SipCallManager @Inject constructor(
    @ApplicationContext private val app: Context,
    // Process-lifetime scope from the graph, so tests can drive this class with their own dispatcher
    // instead of a self-built Dispatchers.Default.
    @ApplicationScope private val scope: CoroutineScope,
) : SipRegistrar {

    private val tag = "SipCallManager"
    private var core: Core? = null
    private var currentCall: Call? = null
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var idleResetJob: Job? = null

    /**
     * A monitored device's live SIP state on the shared core: its [Device] plus the [Account] that carries
     * its PBX registration (null for a peer-to-peer device, which is reached directly rather than
     * registered). Keyed by device id so [synchronizeDevices] can diff the wanted set against what is
     * live and so an incoming call can be traced back to the device that owns the account it rang on.
     */
    private data class ManagedDevice(val device: Device, val account: Account?)

    // Reconciled on the main thread and read from the core listener, which the SDK also runs on the main
    // thread, so no lock is needed.
    private val managed = LinkedHashMap<Long, ManagedDevice>()

    // Probes share one Linphone Factory and one port space, so they run one at a time on rotating ports.
    private val probeLock = Mutex()
    private var probeSlot = 0

    private val _sessionState = MutableStateFlow(SipSessionState())
    val sessionState: StateFlow<SipSessionState> = _sessionState.asStateFlow()

    /**
     * True while the monitor's core exists, i.e. while a live registration is in place that a registration
     * probe would duplicate for the same account. The device form warns on that.
     */
    override val hasActiveCore: Boolean get() = core != null

    /**
     * Invoked when the intercom actually rings this phone (SIP INVITE received), with the id of the device
     * the ring is attributed to by identity, the remote host and a display name. The monitor service sets
     * this so a ring is detected from two independent sources: the 2N HTTP event stream and the SIP call
     * itself, which keeps the doorbell working when SSE has to fall back to status polling (that fallback
     * cannot see rings at all). Duplicate rings from one press are collapsed by the service's per-device
     * debounce, so this must stay a plain notification.
     *
     * Volatile because it is assigned by the monitor service and read on the Linphone core thread. A second
     * consumer should get a Flow rather than another callback property.
     */
    @Volatile
    var onIncomingCall: ((deviceId: Long, remoteHost: String, caller: String) -> Unit)? = null

    private val coreListener = object : CoreListenerStub() {
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            Log.d(tag, "SIP Call State Changed: $state (msg: $message)")
            val event = SipCallMapper.toEvent(state) ?: return
            val rawCallState = if (message.isBlank()) state?.name else "$state: $message"
            val result = SipCallReducer.reduce(
                current = _sessionState.value,
                event = event,
                errorMessage = message,
                rawCallState = rawCallState,
            )
            _sessionState.value = result.state
            // The reducer decided the state; these lines interpret its effects against the native call and audio.
            if (SipCallEffect.ClearCurrentCall in result.effects) {
                currentCall = null
            } else {
                currentCall = call
            }
            if (SipCallEffect.RouteAudioToSpeaker in result.effects) {
                routeAudioToSpeaker(true)
            }
            if (SipCallEffect.ReportIncoming in result.effects) {
                reportIncomingCall(call)
            }
            if (SipCallEffect.ScheduleIdleReset in result.effects) {
                // ENDED and ERROR stay observable for a bounded window rather than being overwritten to IDLE
                // in the same block, which used to leave the call screen with no end or failure signal at all.
                scheduleIdleReset()
            }
        }

        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            Log.i(tag, "SIP PBX Registration state: $state ($message)")
        }
    }

    /**
     * Resolves which monitored device an incoming [call] should be attributed to, then notifies
     * [onIncomingCall]. Identity is decided here rather than by the service because only this class holds
     * the account-to-device mapping: a call received on one of our registered [Account]s is that device;
     * otherwise a peer-to-peer INVITE carries the intercom's own address, and behind a PBX the outbound leg
     * carries the dialed extension. A ring that matches nothing is dropped instead of guessed, closing the
     * old by-count fallback that could open the wrong call screen.
     */
    private fun reportIncomingCall(call: Call) {
        val remoteAddress = call.remoteAddress
        val remoteHost = remoteAddress?.domain.orEmpty()
        val caller = remoteAddress?.username ?: remoteAddress?.asStringUriOnly().orEmpty()
        val displayName = remoteAddress?.displayName ?: caller
        val deviceId = resolveRingingDeviceId(call)
        if (deviceId == null) {
            Log.w(tag, "SIP ring from '$remoteHost' (dialed '${call.callLog?.toAddress?.username}') matches none of " +
                "${managed.size} monitored device(s), ignoring")
            return
        }
        onIncomingCall?.invoke(deviceId, remoteHost, displayName)
    }

    /** The id of the device [call] belongs to, or null when none of the identity signals match. */
    private fun resolveRingingDeviceId(call: Call): Long? {
        // 1. Peer-to-peer: the intercom dials the phone directly, so the remote address is the device itself.
        val remoteHost = call.remoteAddress?.domain
        if (!remoteHost.isNullOrBlank()) {
            managed.entries.firstOrNull { it.value.device.ipAddress == remoteHost }?.let { return it.key }
        }
        // 2. Behind a PBX the outbound leg carries the dialed extension, which is the device's SIP user.
        val dialed = call.callLog?.toAddress?.username
        if (!dialed.isNullOrBlank()) {
            managed.entries.firstOrNull { it.value.device.sipUser == dialed }?.let { return it.key }
        }
        return null
    }

    /** Builds the monitor's Linphone core. Call on the main thread, which is where the SDK runs its loop. */
    fun initialize() {
        if (core != null) return

        try {
            configureLogging()

            val factory = Factory.instance()
            val newCore = factory.createCore(null, null, app)

            newCore.addListener(coreListener)
            newCore.isMicEnabled = true
            newCore.isEchoCancellationEnabled = true
            newCore.isEchoLimiterEnabled = true

            // Setup audio device routing
            val audioDevice = newCore.audioDevices.firstOrNull { it.type == AudioDevice.Type.Speaker }
            if (audioDevice != null) {
                newCore.outputAudioDevice = audioDevice
            }

            // Configure Transports (UDP & TCP on Port 5060)
            val transports = newCore.transports
            transports.udpPort = SipCallTiming.DEFAULT_SIP_PORT
            transports.tcpPort = SipCallTiming.DEFAULT_SIP_PORT
            transports.tlsPort = -1
            newCore.transports = transports

            newCore.start()
            core = newCore
            Log.i(tag, "Linphone Core initialized successfully on port ${SipCallTiming.DEFAULT_SIP_PORT}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize Linphone Core", e)
        }
    }

    /**
     * Linphone keeps every registration answer to itself unless logging is routed out, which is the only way
     * to tell a REGISTER the PBX refused from one that never left the phone. Debug builds pay the noise;
     * releases stay quiet. Replaces the deprecated `Factory.setDebugMode`.
     */
    private fun configureLogging() {
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val logService = Factory.instance().loggingService
        // The domain must be set before the level, and it is the tag the messages are filed under.
        logService.setDomain(LOG_DOMAIN)
        if (debuggable) {
            if (!loggingListenerAttached) {
                logService.addListener(loggingListener)
                loggingListenerAttached = true
            }
            logService.setLogLevel(LogLevel.Debug)
        } else {
            logService.setLogLevel(LogLevel.Error)
        }
    }

    private var loggingListenerAttached = false
    private val loggingListener = object : LoggingServiceListener {
        override fun onLogMessageWritten(
            logService: LoggingService,
            domain: String,
            level: LogLevel,
            message: String
        ) {
            when (level) {
                LogLevel.Debug, LogLevel.Trace -> Log.d(LOG_DOMAIN, message)
                LogLevel.Warning -> Log.w(LOG_DOMAIN, message)
                LogLevel.Error, LogLevel.Fatal -> Log.e(LOG_DOMAIN, message)
                else -> Log.i(LOG_DOMAIN, message)
            }
        }
    }

    /**
     * Reconciles the shared core's accounts and listen port against the full set of monitored [devices], so
     * peer-to-peer and PBX devices coexist instead of the last-configured one retiring the others.
     *
     * Takes the whole list rather than one device precisely because retiring a device now has to be relative
     * to what should still be live: each PBX device owns an [Account] that is added, replaced or removed on
     * its own, and the core's inbound port follows the (single) peer-to-peer device rather than being wiped
     * on every reconfigure.
     *
     * Suspend because every call below mutates a core whose loop the SDK schedules on the main thread, and
     * Linphone's API is not thread-safe.
     */
    suspend fun synchronizeDevices(devices: List<Device>) {
        withContext(Dispatchers.Main.immediate) {
            val c = core ?: return@withContext
            val wanted = devices.associateBy { it.id }

            // Retire devices that were removed or disabled: withdraw their account + credentials.
            for (id in (managed.keys - wanted.keys).toList()) {
                retireDevice(c, id)
            }

            // Add or reconfigure each device whose row changed; leave untouched ones registered.
            for (device in wanted.values) {
                if (managed[device.id]?.device == device) continue
                retireDevice(c, device.id)
                applyDevice(c, device)
            }

            // The core owns one inbound socket. Peer-to-peer intercoms dial it directly, so it binds that
            // device's local port; a PBX-only set keeps the default and registers outbound.
            applyInboundPort(c, wanted.values.filter { it.sipMode == SipMode.PEER_TO_PEER })
        }
    }

    /**
     * Creates the live SIP state for [device] on [sipCore]. A PBX device gets its own register-enabled
     * [Account] plus an [AuthInfo]; a peer-to-peer device gets no account (the core's inbound socket receives
     * its direct INVITE) and is only recorded so a ring can be attributed to it; a disabled device keeps
     * nothing.
     */
    private fun applyDevice(sipCore: Core, device: Device) {
        when (device.sipMode) {
            SipMode.PEER_TO_PEER -> {
                Log.i(tag, "Monitoring SIP in Direct Peer-to-Peer mode on port ${device.sipLocalPort}")
                managed[device.id] = ManagedDevice(device, account = null)
            }
            SipMode.PBX_REGISTRAR -> {
                val host = device.sipServerHost ?: return
                val user = device.sipUser ?: return
                val port = device.sipServerPort ?: SipCallTiming.DEFAULT_SIP_PORT
                val account = addPbxAccount(sipCore, host, port, user, device.sipPassword ?: "")
                managed[device.id] = ManagedDevice(device, account)
            }
            SipMode.DISABLED -> {
                Log.i(tag, "SIP disabled for ${device.name}")
            }
        }
    }

    /**
     * Withdraws the account [device] was registered under and forgets its credentials, then drops it from the
     * live set. Only `removeAccount` makes liblinphone send the unregister REGISTER - clearing the account
     * list wholesale would take other devices' registrations with it - so without this a retired account
     * stays bound on the PBX until its expiry, and its stale AuthInfo could still answer a 401 challenge
     * after an edited password.
     */
    private fun retireDevice(sipCore: Core, deviceId: Long) {
        val retired = managed.remove(deviceId) ?: return
        val account = retired.account ?: return
        sipCore.removeAccount(account)
        val user = retired.device.sipUser
        if (user != null) {
            sipCore.findAuthInfo(retired.device.sipServerHost, user, null)?.let { sipCore.removeAuthInfo(it) }
        }
    }

    /**
     * Registers [device] set up as a PBX account on [sipCore]: credentials, identity and a register-enabled
     * account. One implementation shared by the live path ([applyDevice]) and [probeRegistration], so a
     * registration that tests green cannot then silently behave differently once saved.
     */
    private fun addPbxAccount(
        sipCore: Core,
        host: String,
        port: Int,
        user: String,
        password: String
    ): Account {
        Log.i(tag, "Configuring SIP PBX Registrar: user=$user server=$host:$port")
        val factory = Factory.instance()
        sipCore.addAuthInfo(factory.createAuthInfo(user, null, password, null, null, host))

        val params = sipCore.createAccountParams()
        params.setIdentityAddress(factory.createAddress("sip:$user@$host"))
        params.setServerAddress(factory.createAddress("sip:$host:$port"))
        params.setRealm(host)
        params.setRegisterEnabled(true)
        return sipCore.createAccount(params).also { sipCore.addAccount(it) }
    }

    /**
     * Points the core's single inbound socket at the peer-to-peer device's local port, or back at the default
     * when no device is reached directly. More than one distinct peer-to-peer port cannot bind one socket, so
     * the first is used and the rest are reported - a real configuration limit the previous single-core model
     * shared but hid behind the "last wins" reconfigure.
     */
    private fun applyInboundPort(sipCore: Core, peerToPeerDevices: List<Device>) {
        val ports = peerToPeerDevices.map { it.sipLocalPort }.distinct()
        if (ports.size > 1) {
            Log.w(tag, "${peerToPeerDevices.size} peer-to-peer devices want distinct ports $ports; " +
                "only ${ports.first()} can be bound on the shared core")
        }
        val port = ports.firstOrNull() ?: SipCallTiming.DEFAULT_SIP_PORT
        val transports = sipCore.transports
        if (transports.udpPort == port && transports.tcpPort == port) return
        transports.udpPort = port
        transports.tcpPort = port
        sipCore.transports = transports
        Log.i(tag, "SIP inbound port set to $port")
    }

    /**
     * Asks a PBX registrar whether the credentials as typed would work, on a **throw-away core**: the
     * monitor's live registration, its accounts and the audio routing are never touched, and nothing is
     * written to Room. The answer arrives as [SipProbeResult] instead of logcat.
     *
     * Serialised by [probeLock] and rotating through [SipCallTiming.probeLocalPort]: a stopped core hands its
     * socket back only once the wrapper releases the native object, so a second probe that reused the same port
     * could find it bound, send nothing, and report a timeout that has nothing to do with the credentials.
     *
     * The answer is reported, then the core is shut down on a later main loop message; see the teardown below
     * for why stopping it from inside the registration callback kills the process.
     */
    override suspend fun probeRegistration(device: Device): SipProbeResult = probeLock.withLock {
        val input = when (val probeInput = SipProbeInputValidator.validate(device)) {
            is SipProbeInput.Missing -> return@withLock SipProbeResult.MissingFields(probeInput.field)
            is SipProbeInput.Ready -> probeInput
        }
        val host = input.host
        val user = input.user
        val port = input.port
        val server = "$host:$port"
        probeSlot = (probeSlot + 1) % SipCallTiming.SIP_PROBE_PORT_SLOTS
        val localPort = SipCallTiming.probeLocalPort(probeSlot)

        val registration = CompletableDeferred<SipProbeResult>()
        // Kept for the no-answer case: nothing recorded means the REGISTER never left the phone, Progress
        // means it did and the registrar ignored it. Those need different fixes.
        val lastState = AtomicReference<RegistrationState?>()
        val listener = object : CoreListenerStub() {
            override fun onAccountRegistrationStateChanged(
                core: Core,
                account: Account,
                state: RegistrationState?,
                message: String
            ) {
                if (state != null) lastState.set(state)
                Log.i(tag, "SIP probe on $server registration state: $state ($message)")
                val outcome = SipProbeOutcomeMapper.toOutcome(
                    event = SipCallMapper.registrationEventOf(state),
                    stateName = state?.name ?: "",
                    message = message,
                    server = server,
                )
                if (outcome != null) registration.complete(outcome)
            }
        }

        withContext(Dispatchers.Main.immediate) {
            // Created, configured and started on the main thread: the SDK schedules the core's iterate() there
            // and its API is not thread-safe, so a core assembled on an IO worker never reliably sends.
            val probe = Factory.instance().createCore(null, null, app)
            try {
                // Its own port, never the live core's, and no TLS: this is what the app can actually offer.
                val transports = probe.transports
                transports.udpPort = localPort
                transports.tcpPort = localPort
                transports.tlsPort = -1
                probe.transports = transports

                addPbxAccount(probe, host, port, user, input.password)

                // Attached before start(): a rejection can arrive on the core thread's very first pass.
                probe.addListener(listener)
                probe.start()
                Log.i(tag, "SIP probe registering sip:$user@$server from local port $localPort")

                withTimeoutOrNull(SipCallTiming.SIP_PROBE_TIMEOUT_MS.milliseconds) { registration.await() }
                    ?: SipProbeResult.NoAnswer(server, lastState.get()?.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A malformed address or a core that refuses to start is a real answer the user must see.
                Log.e(tag, "SIP probe on $server could not run", e)
                SipProbeResult.Rejected("LocalError", e.localizedMessage ?: e.javaClass.simpleName)
            } finally {
                // NonCancellable: leaving the screen cancels the caller, and a probe left running keeps its
                // port bound on the phone.
                //
                // Dispatchers.Main and NOT Main.immediate on purpose. A successful probe resumes here as the
                // direct continuation of onAccountRegistrationStateChanged, which liblinphone calls from inside
                // Account::setState while it is dispatching the REGISTER answer. Main.immediate would keep
                // that stack alive and stop() would tear down the belle-sip stack underneath the caller that
                // still has work left after the notification - its next belle_sip_main_loop_add_source() then
                // runs on a null main loop and the process dies with SIGSEGV ("reentrancy detected" right
                // before the crash is the fingerprint). A plain Main dispatch queues behind the running
                // iterate() message instead of nesting inside it.
                withContext(NonCancellable + Dispatchers.Main) {
                    probe.removeListener(listener)
                    // stop() and nothing else: shutting the core down is the SDK's own graceful unregister, so
                    // the account this probe registered is withdrawn with it.
                    probe.stop()
                }
            }
        }.also { Log.i(tag, "SIP probe on $server answered $it") }
    }

    /** Drops the terminal call state back to IDLE after [SipCallTiming.POST_CALL_IDLE_MS], re-armed per terminal event. */
    private fun scheduleIdleReset() {
        idleResetJob?.cancel()
        idleResetJob = scope.launch {
            delay(SipCallTiming.POST_CALL_IDLE_MS.milliseconds)
            _sessionState.value = SipSessionState(state = CallUiState.IDLE)
        }
    }

    fun acceptCall() {
        val call = currentCall
        if (call == null) {
            Log.w(tag, "Accept ignored: no current SIP call to answer")
            return
        }
        // Accept any pre-answer incoming state. Nothing in this app calls startRinging(), so today the call
        // stays in IncomingReceived; relying on that single value would make the answer button a silent
        // no-op as soon as the core advances the call to early media (Linphone 5 has no IncomingRinging).
        if (!SipCallMapper.shouldAccept(call.state)) {
            Log.w(tag, "Accept ignored: call is in ${call.state}, not incoming")
            return
        }
        val params = core?.createCallParams(call)
        params?.isVideoEnabled = false // Audio intercom call
        call.acceptWithParams(params)
        routeAudioToSpeaker(true)
        Log.i(tag, "Accepted incoming SIP call")
    }

    fun terminateCall() {
        currentCall?.terminate()
        currentCall = null
        idleResetJob?.cancel()
        _sessionState.value = SipSessionState(state = CallUiState.IDLE)
        Log.i(tag, "Terminated SIP call")
    }

    fun setMicrophoneMuted(muted: Boolean) {
        core?.isMicEnabled = !muted
        _sessionState.value = _sessionState.value.copy(isMicMuted = muted)
    }

    fun routeAudioToSpeaker(speakerOn: Boolean) {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+ : the speakerphone flags are deprecated; route through the communication
                // device (built-in speaker for hands-free, cleared back to the default/earpiece off).
                if (speakerOn) {
                    val speaker = audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) audioManager.setCommunicationDevice(speaker)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                // Pre-31 fallback: isSpeakerphoneOn is the only speaker-routing API below API 31 while
                // minSdk = 26, so its deprecation warning is intentionally left visible (see 2Do
                // "Lint & deprecation debt"). Fully clearing it would require raising minSdk to 31.
                audioManager.isSpeakerphoneOn = speakerOn
            }
            _sessionState.value = _sessionState.value.copy(isSpeakerOn = speakerOn)
        } catch (e: Exception) {
            Log.e(tag, "Failed to toggle speakerphone", e)
        }
    }

    /**
     * Stops the monitor's core. Must stay on the main thread; its only caller is the service's onDestroy(), which
     * is its own main loop message - a core callback would forbid tearing the core down from inside the
     * notification, see the probe's teardown.
     */
    fun destroy() {
        try {
            onIncomingCall = null
            idleResetJob?.cancel()
            // The manager scope is intentionally not cancelled: this is a process singleton that a restarted
            // monitor service reuses, and a cancelled scope would silently drop the ENDED -> IDLE reset.
            core?.let { c ->
                // stop() and nothing else: shutting a core down is liblinphone's own unregister, so the
                // accounts this core registered are withdrawn with it. removeAccount() here would only
                // duplicate that work while risking an Account being freed under an in-flight answer.
                c.removeListener(coreListener)
                c.stop()
            }
            core = null
            managed.clear()
            if (loggingListenerAttached) {
                Factory.instance().loggingService.removeListener(loggingListener)
                loggingListenerAttached = false
            }
        } catch (e: Exception) {
            Log.e(tag, "Error stopping Linphone Core", e)
        }
    }

    private companion object {
        /** Logcat tag + Linphone logging domain for the SDK's own messages in debug builds. */
        const val LOG_DOMAIN = "EntryRecorderSIP"
    }
}
