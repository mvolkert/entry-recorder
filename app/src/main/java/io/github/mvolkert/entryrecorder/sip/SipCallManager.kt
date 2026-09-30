package io.github.mvolkert.entryrecorder.sip

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.SipMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

// Holds Application (not an arbitrary Context) so the process-lifetime singleton in the
// companion never retains an Activity/Service — this is what clears the StaticFieldLeak warning.
class SipCallManager private constructor(private val app: Application) {

    private val tag = "SipCallManager"
    private var core: Core? = null
    private var currentCall: Call? = null
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleResetJob: Job? = null

    private val _sessionState = MutableStateFlow(SipSessionState())
    val sessionState: StateFlow<SipSessionState> = _sessionState.asStateFlow()

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

    fun initialize() {
        if (core != null) return

        try {
            val factory = Factory.instance()
            factory.setDebugMode(false, "EntryRecorderSIP")
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
     * Configure SIP settings based on device configuration (Peer-to-Peer vs PBX registrar)
     */
    // TODO(sip): Core ProxyConfig APIs below (createProxyConfig/edit/done/addProxyConfig/
    //   defaultProxyConfig/clearProxyConfig + address/register setters) and Factory.setDebugMode are
    //   deprecated in Linphone 5.x in favor of the Account API. Migration deferred pending real
    //   device/PBX validation (2Do Phase 2).
    fun configureDeviceSip(device: DeviceEntity) {
        val c = core ?: return

        when (device.sipMode) {
            SipMode.PEER_TO_PEER -> {
                Log.i(tag, "Configuring SIP in Direct Peer-to-Peer mode on port ${device.sipLocalPort}")
                // Clear proxy configs
                c.clearProxyConfig()
                c.clearAllAuthInfo()
                val transports = c.transports
                transports.udpPort = device.sipLocalPort
                transports.tcpPort = device.sipLocalPort
                c.transports = transports
            }
            SipMode.PBX_REGISTRAR -> {
                val host = device.sipServerHost ?: return
                val port = device.sipServerPort ?: 5060
                val user = device.sipUser ?: return
                val pwd = device.sipPassword ?: ""

                Log.i(tag, "Configuring SIP PBX Registrar: user=$user server=$host:$port")
                val factory = Factory.instance()

                // Auth Info
                val authInfo = factory.createAuthInfo(user, null, pwd, null, null, host)
                c.addAuthInfo(authInfo)

                // Account / Proxy Config
                val identity = "sip:$user@$host"
                val proxyServer = "sip:$host:$port"

                val proxyConfig = c.createProxyConfig()
                proxyConfig.edit()
                proxyConfig.identityAddress = factory.createAddress(identity)
                proxyConfig.serverAddr = proxyServer
                proxyConfig.isRegisterEnabled = true
                proxyConfig.done()

                c.addProxyConfig(proxyConfig)
                c.defaultProxyConfig = proxyConfig
            }
            SipMode.DISABLED -> {
                c.clearProxyConfig()
            }
        }
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

    fun destroy() {
        try {
            onIncomingCall = null
            idleResetJob?.cancel()
            // The manager scope is intentionally not cancelled: this is a process singleton that a restarted
            // monitor service reuses, and a cancelled scope would silently drop the ENDED -> IDLE reset.
            core?.stop()
            core = null
        } catch (e: Exception) {
            Log.e(tag, "Error stopping Linphone Core", e)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: SipCallManager? = null

        fun getInstance(context: Context): SipCallManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SipCallManager(context.applicationContext as Application).also { INSTANCE = it }
            }
        }
    }
}
