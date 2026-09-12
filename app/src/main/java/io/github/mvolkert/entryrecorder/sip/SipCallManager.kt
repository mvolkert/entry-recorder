package io.github.mvolkert.entryrecorder.sip

import android.content.Context
import android.media.AudioManager
import android.util.Log
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.SipMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.*

enum class CallUiState {
    IDLE,
    RINGING_INCOMING,
    CONNECTED,
    ENDED,
    ERROR
}

data class SipSessionState(
    val state: CallUiState = CallUiState.IDLE,
    val callerAddress: String? = null,
    val callerDisplayName: String? = null,
    val isMicMuted: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val errorMessage: String? = null
)

class SipCallManager private constructor(private val context: Context) {

    private val tag = "SipCallManager"
    private var core: Core? = null
    private var currentCall: Call? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _sessionState = MutableStateFlow(SipSessionState())
    val sessionState: StateFlow<SipSessionState> = _sessionState.asStateFlow()

    private val coreListener = object : CoreListenerStub() {
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            Log.d(tag, "SIP Call State Changed: $state (msg: $message)")
            when (state) {
                Call.State.IncomingReceived -> {
                    currentCall = call
                    val remoteAddress = call.remoteAddress
                    val caller = remoteAddress.username ?: remoteAddress.asStringUriOnly()
                    val displayName = remoteAddress.displayName ?: caller

                    _sessionState.value = SipSessionState(
                        state = CallUiState.RINGING_INCOMING,
                        callerAddress = caller,
                        callerDisplayName = displayName
                    )
                }
                Call.State.StreamsRunning, Call.State.Connected -> {
                    currentCall = call
                    routeAudioToSpeaker(true)
                    _sessionState.value = _sessionState.value.copy(
                        state = CallUiState.CONNECTED,
                        errorMessage = null
                    )
                }
                Call.State.End, Call.State.Released -> {
                    currentCall = null
                    _sessionState.value = SipSessionState(state = CallUiState.ENDED)
                    // Reset to idle after a moment
                    _sessionState.value = SipSessionState(state = CallUiState.IDLE)
                }
                Call.State.Error -> {
                    currentCall = null
                    _sessionState.value = SipSessionState(
                        state = CallUiState.ERROR,
                        errorMessage = message
                    )
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
            val newCore = factory.createCore(null, null, context)

            newCore.addListener(coreListener)
            newCore.isMicEnabled = true
            newCore.enableEchoCancellation(true)
            newCore.enableEchoLimiter(true)

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
                proxyConfig.enableRegister(true)
                proxyConfig.done()

                c.addProxyConfig(proxyConfig)
                c.defaultProxyConfig = proxyConfig
            }
            SipMode.DISABLED -> {
                c.clearProxyConfig()
            }
        }
    }

    fun acceptCall() {
        val call = currentCall
        if (call != null && call.state == Call.State.IncomingReceived) {
            val params = core?.createCallParams(call)
            params?.enableVideo(false) // Audio intercom call
            call.acceptWithParams(params)
            routeAudioToSpeaker(true)
            Log.i(tag, "Accepted incoming SIP call")
        }
    }

    fun terminateCall() {
        currentCall?.terminate()
        currentCall = null
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
            audioManager.isSpeakerphoneOn = speakerOn
            _sessionState.value = _sessionState.value.copy(isSpeakerOn = speakerOn)
        } catch (e: Exception) {
            Log.e(tag, "Failed to toggle speakerphone", e)
        }
    }

    fun destroy() {
        try {
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
                INSTANCE ?: SipCallManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
