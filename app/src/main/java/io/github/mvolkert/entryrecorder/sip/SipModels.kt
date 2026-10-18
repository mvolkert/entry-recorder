package io.github.mvolkert.entryrecorder.sip

/**
 * The SIP surface the rest of the app imports: what the call screen renders and what the device form's
 * registration test answers with. Relocated out of `SipCallManager` so consumers depend on these types
 * rather than on the class that owns the Linphone core; all four are declared exactly as they were.
 */

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
