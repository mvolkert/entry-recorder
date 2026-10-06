package io.github.mvolkert.entryrecorder.sip

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.SipMode
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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

// Holds Application (not an arbitrary Context) so the process-lifetime singleton in the
// companion never retains an Activity/Service — this is what clears the StaticFieldLeak warning.
class SipCallManager private constructor(private val app: Application) {

    private val tag = "SipCallManager"
    private var core: Core? = null
    private var currentCall: Call? = null
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleResetJob: Job? = null

    // Probes share one Linphone Factory and one port space, so they run one at a time on rotating ports.
    private val probeLock = Mutex()
    private var probeSlot = 0

    private val _sessionState = MutableStateFlow(SipSessionState())
    val sessionState: StateFlow<SipSessionState> = _sessionState.asStateFlow()

    /**
     * True while the monitor's core exists, i.e. while a live registration is in place that a registration
     * probe would duplicate for the same account. The device form warns on that.
     */
    val hasActiveCore: Boolean get() = core != null

    /**
     * Invoked when the intercom actually rings this phone (SIP INVITE received), with the remote host and
     * a display name. The monitor service sets this so a ring is detected from two independent sources:
     * the 2N HTTP event stream and the SIP call itself, which keeps the doorbell working when SSE has to
     * fall back to status polling (that fallback cannot see rings at all). Duplicate rings from one press
     * are collapsed by the service's per-device debounce, so this must stay a plain notification.
     *
     * Volatile because it is assigned by the monitor service and read on the Linphone core thread. A second
     * consumer should get a Flow rather than another callback property.
     */
    @Volatile
    var onIncomingCall: ((remoteHost: String, caller: String) -> Unit)? = null

    private val coreListener = object : CoreListenerStub() {
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            Log.d(tag, "SIP Call State Changed: $state (msg: $message)")
            val rawCallState = if (message.isBlank()) state?.name else "$state: $message"
            when (state) {
                Call.State.IncomingReceived -> {
                    currentCall = call
                    val remoteAddress = call.remoteAddress
                    val caller = remoteAddress.username ?: remoteAddress.asStringUriOnly()
                    val displayName = remoteAddress.displayName ?: caller

                    _sessionState.value = SipSessionState(
                        state = CallUiState.RINGING_INCOMING,
                        rawCallState = rawCallState
                    )
                    // Every INVITE is reported; the monitor service's per-device debounce is what collapses
                    // this against the 2N event stream's own ring notification for the same press.
                    // Address.domain is the SIP host part, i.e. the intercom's IP in peer-to-peer mode.
                    onIncomingCall?.invoke(remoteAddress.domain ?: "", displayName)
                }
                Call.State.StreamsRunning, Call.State.Connected -> {
                    currentCall = call
                    routeAudioToSpeaker(true)
                    _sessionState.value = _sessionState.value.copy(
                        state = CallUiState.CONNECTED,
                        errorMessage = null,
                        rawCallState = rawCallState
                    )
                }
                Call.State.End, Call.State.Released -> {
                    currentCall = null
                    // ENDED has to stay observable: it was previously overwritten by IDLE in the same block,
                    // so no observer could ever see it and the call screen had no end signal at all.
                    _sessionState.value = SipSessionState(
                        state = CallUiState.ENDED,
                        rawCallState = rawCallState
                    )
                    scheduleIdleReset()
                }
                Call.State.Error -> {
                    currentCall = null
                    _sessionState.value = SipSessionState(
                        state = CallUiState.ERROR,
                        errorMessage = message,
                        rawCallState = rawCallState
                    )
                    // Same bounded window as ENDED: a failed call used to stay in the flow forever, so the
                    // next unrelated screen would render a stale failure with dead accept/decline buttons.
                    scheduleIdleReset()
                }
                else -> {}
            }
        }

        override fun onRegistrationStateChanged(
            core: Core,
            cfg: ProxyConfig,
            state: RegistrationState?,
            message: String
        ) {
            Log.i(tag, "SIP PBX Registration state: $state ($message)")
        }
    }

    /** Builds the monitor's Linphone core. Call on the main thread, which is where the SDK runs its loop. */
    fun initialize() {
        if (core != null) return

        try {
            val factory = Factory.instance()
            // Linphone keeps every registration answer to itself unless logging is on, which is the only way
            // to tell a REGISTER the PBX refused from one that never left the phone. Debug builds pay the
            // noise; releases stay quiet.
            factory.setDebugMode(
                (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                "EntryRecorderSIP"
            )
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
            transports.udpPort = 5060
            transports.tcpPort = 5060
            transports.tlsPort = -1
            newCore.transports = transports

            newCore.start()
            core = newCore
            Log.i(tag, "Linphone Core initialized successfully on port 5060")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize Linphone Core", e)
        }
    }

    /**
     * Points the monitor's core at [device]'s SIP setup, retiring whatever that core had registered first.
     *
     * Suspend because every call below mutates a core whose loop the SDK schedules on the main thread, and
     * Linphone's API is not thread-safe; this used to run on the service's IO dispatcher.
     */
    // TODO(sip): Core ProxyConfig APIs below (createProxyConfig/edit/done/addProxyConfig/
    //   defaultProxyConfig/removeProxyConfig + address/register setters) and Factory.setDebugMode are
    //   deprecated in Linphone 5.x in favor of the Account API. Migration deferred pending real
    //   device/PBX validation (2Do Phase 2).
    suspend fun configureDeviceSip(device: DeviceEntity) {
        withContext(Dispatchers.Main.immediate) {
            val c = core ?: return@withContext

            when (device.sipMode) {
                SipMode.PEER_TO_PEER -> {
                    Log.i(tag, "Configuring SIP in Direct Peer-to-Peer mode on port ${device.sipLocalPort}")
                    unregisterAndClearAuth(c)
                    val transports = c.transports
                    transports.udpPort = device.sipLocalPort
                    transports.tcpPort = device.sipLocalPort
                    c.transports = transports
                }
                SipMode.PBX_REGISTRAR -> {
                    val host = device.sipServerHost ?: return@withContext
                    val user = device.sipUser ?: return@withContext
                    // addAuthInfo/addProxyConfig only ever append, so an edited credential used to leave the
                    // retired account registered and refreshing forever: the PBX held two contacts for one
                    // address and the older AuthInfo could still be the one answering the 401 challenge.
                    unregisterAndClearAuth(c)
                    applyPbxRegistrar(
                        sipCore = c,
                        host = host,
                        port = device.sipServerPort ?: DEFAULT_SIP_PORT,
                        user = user,
                        password = device.sipPassword ?: ""
                    )
                }
                SipMode.DISABLED -> {
                    unregisterAndClearAuth(c)
                }
            }
        }
    }

    /**
     * Withdraws every registration on [sipCore] and forgets its credentials. Only `removeProxyConfig` makes
     * liblinphone send the unregister REGISTER - `clearProxyConfig` merely drops the entries from the config -
     * so without this a retired account stays bound on the PBX until its expiry.
     */
    private fun unregisterAndClearAuth(sipCore: Core) {
        sipCore.proxyConfigList.toList().forEach { proxy -> sipCore.removeProxyConfig(proxy) }
        sipCore.clearAllAuthInfo()
    }

    /**
     * Points [sipCore] at a PBX registrar: credentials, identity and a register-enabled proxy config. One
     * implementation shared by the live path and [probeRegistration], so a registration that tests green
     * cannot then silently behave differently once saved.
     */
    private fun applyPbxRegistrar(sipCore: Core, host: String, port: Int, user: String, password: String) {
        Log.i(tag, "Configuring SIP PBX Registrar: user=$user server=$host:$port")
        val factory = Factory.instance()

        sipCore.addAuthInfo(factory.createAuthInfo(user, null, password, null, null, host))

        val proxyConfig = sipCore.createProxyConfig()
        proxyConfig.edit()
        proxyConfig.identityAddress = factory.createAddress("sip:$user@$host")
        proxyConfig.serverAddr = "sip:$host:$port"
        proxyConfig.isRegisterEnabled = true
        proxyConfig.done()

        sipCore.addProxyConfig(proxyConfig)
        sipCore.defaultProxyConfig = proxyConfig
    }

    /**
     * Asks a PBX registrar whether the credentials as typed would work, on a **throw-away core**: the
     * monitor's live registration, its proxy configs and the audio routing are never touched, and nothing is
     * written to Room. The answer arrives as [SipProbeResult] instead of logcat.
     *
     * Serialised by [probeLock] and rotating through [SipCallTiming.probeLocalPort]: a stopped core hands its
     * socket back only once the wrapper releases the native object, so a second probe that reused the same port
     * could find it bound, send nothing, and report a timeout that has nothing to do with the credentials.
     */
    suspend fun probeRegistration(device: DeviceEntity): SipProbeResult = probeLock.withLock {
        if (device.sipMode != SipMode.PBX_REGISTRAR) {
            return@withLock SipProbeResult.MissingFields(SipMissingField.CALLING_MODE)
        }
        val host = device.sipServerHost?.takeIf { it.isNotBlank() }
            ?: return@withLock SipProbeResult.MissingFields(SipMissingField.PBX_HOST)
        val user = device.sipUser?.takeIf { it.isNotBlank() }
            ?: return@withLock SipProbeResult.MissingFields(SipMissingField.SIP_USER)

        val port = device.sipServerPort ?: DEFAULT_SIP_PORT
        val server = "$host:$port"
        probeSlot = (probeSlot + 1) % SipCallTiming.SIP_PROBE_PORT_SLOTS
        val localPort = SipCallTiming.probeLocalPort(probeSlot)

        val registration = CompletableDeferred<SipProbeResult>()
        // Kept for the no-answer case: nothing recorded means the REGISTER never left the phone, Progress
        // means it did and the registrar ignored it. Those need different fixes.
        val lastState = AtomicReference<RegistrationState?>()
        val listener = object : CoreListenerStub() {
            override fun onRegistrationStateChanged(
                core: Core,
                cfg: ProxyConfig,
                state: RegistrationState?,
                message: String
            ) {
                if (state != null) lastState.set(state)
                Log.i(tag, "SIP probe on $server registration state: $state ($message)")
                val outcome = when (state) {
                    RegistrationState.Ok -> SipProbeResult.Registered(server)
                    // Cleared is where a refused account ends up once its 401/403 retry expires.
                    RegistrationState.Failed, RegistrationState.Cleared -> {
                        SipProbeResult.Rejected(state.name, message.ifBlank { state.name })
                    }
                    else -> null
                }
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

                applyPbxRegistrar(probe, host, port, user, device.sipPassword ?: "")

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
                // NonCancellable: leaving the screen cancels the caller, and a probe left running keeps both
                // its registration on the PBX and its port bound on the phone.
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    probe.removeListener(listener)
                    unregisterAndClearAuth(probe)
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
        if (call.state != Call.State.IncomingReceived && call.state != Call.State.IncomingEarlyMedia) {
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

    /** Stops the monitor's core. Must stay on the main thread; its only caller is the service's onDestroy(). */
    fun destroy() {
        try {
            onIncomingCall = null
            idleResetJob?.cancel()
            // The manager scope is intentionally not cancelled: this is a process singleton that a restarted
            // monitor service reuses, and a cancelled scope would silently drop the ENDED -> IDLE reset.
            core?.let { c ->
                // Withdraw the registrations first: stop() alone leaves this phone's binding on the PBX until
                // the registration expires, while initialize() would go on to build a second core for the
                // same account after a monitoring restart inside this process.
                unregisterAndClearAuth(c)
                c.stop()
            }
            core = null
        } catch (e: Exception) {
            Log.e(tag, "Error stopping Linphone Core", e)
        }
    }

    companion object {
        /** Registrar port used when the device row carries none; the same default the form starts from. */
        private const val DEFAULT_SIP_PORT = 5060

        @Volatile
        private var INSTANCE: SipCallManager? = null

        fun getInstance(context: Context): SipCallManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SipCallManager(context.applicationContext as Application).also { INSTANCE = it }
            }
        }
    }
}
