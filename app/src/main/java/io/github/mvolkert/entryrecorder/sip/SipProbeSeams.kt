package io.github.mvolkert.entryrecorder.sip

import io.github.mvolkert.entryrecorder.data.model.SipMode
import io.github.mvolkert.entryrecorder.domain.model.Device

/**
 * The registrar's answer, in the app's own vocabulary, so [SipProbeOutcomeMapper] never reads a native
 * `RegistrationState`. [SipCallMapper] is the one place that collapses the Linphone enum into this.
 */
enum class SipRegistrationEvent { Ok, Failed, Cleared, Other }

/** The result of checking a device row before a probe can ask a registrar anything. */
sealed interface SipProbeInput {
    /** The form is not ready to be tested yet; names which field is missing. */
    data class Missing(val field: SipMissingField) : SipProbeInput

    /** Everything a probe needs, already defaulted, so the live path and the probe cannot disagree. */
    data class Ready(val host: String, val port: Int, val user: String, val password: String) : SipProbeInput
}

/**
 * Validates the fields [SipCallManager.probeRegistration] needs, in priority order: a calling mode that is
 * not a PBX registrar, then a blank host, then a blank user. Extracted so the guard rails a device form
 * relies on are testable without a core.
 */
object SipProbeInputValidator {
    fun validate(device: Device): SipProbeInput {
        if (device.sipMode != SipMode.PBX_REGISTRAR) {
            return SipProbeInput.Missing(SipMissingField.CALLING_MODE)
        }
        val host = device.sipServerHost?.takeIf { it.isNotBlank() }
            ?: return SipProbeInput.Missing(SipMissingField.PBX_HOST)
        val user = device.sipUser?.takeIf { it.isNotBlank() }
            ?: return SipProbeInput.Missing(SipMissingField.SIP_USER)
        return SipProbeInput.Ready(
            host = host,
            port = device.sipServerPort ?: SipCallTiming.DEFAULT_SIP_PORT,
            user = user,
            password = device.sipPassword ?: "",
        )
    }
}

/**
 * Maps a registration answer onto the outcome a probe reports: Ok is accepted, Failed and Cleared are a
 * refusal (the message the registrar sent, or the state name when it sent none), anything else means no
 * answer yet and the probe keeps waiting. Split out so the accepted/refused/pending rules are testable
 * without a live registrar.
 */
object SipProbeOutcomeMapper {
    /**
     * [stateName] is the raw `RegistrationState` name, reported verbatim in [SipProbeResult.Rejected] so the
     * device form shows what the registrar actually said.
     */
    fun toOutcome(event: SipRegistrationEvent, stateName: String, message: String, server: String): SipProbeResult? =
        when (event) {
            SipRegistrationEvent.Ok -> SipProbeResult.Registered(server)
            SipRegistrationEvent.Failed, SipRegistrationEvent.Cleared ->
                SipProbeResult.Rejected(stateName, message.ifBlank { stateName })
            SipRegistrationEvent.Other -> null
        }
}
