package io.github.mvolkert.entryrecorder.sip

import io.github.mvolkert.entryrecorder.data.model.SipMode
import io.github.mvolkert.entryrecorder.domain.model.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the two pure decisions that used to be inlined in [SipCallManager.probeRegistration]: which device
 * fields must be present before a probe can ask a registrar anything, and how a registration answer maps to
 * the reported outcome. The native `RegistrationState` never appears here — the manager's mapper collapses it
 * to [SipRegistrationEvent] first — so the accepted / refused / pending rules are testable without a core.
 */
class SipProbeMappingTest {

    private fun device(
        mode: SipMode = SipMode.PBX_REGISTRAR,
        host: String? = "pbx.local",
        user: String? = "100",
        port: Int? = 5061,
        password: String? = "secret",
    ) = Device(name = "Front Door", ipAddress = "192.168.1.50", sipMode = mode, sipServerHost = host, sipUser = user, sipServerPort = port, sipPassword = password)

    // --- input validation -------------------------------------------------

    @Test
    fun `a non-registrar calling mode is not testable`() {
        val result = SipProbeInputValidator.validate(device(mode = SipMode.PEER_TO_PEER))

        assertEquals(SipProbeInput.Missing(SipMissingField.CALLING_MODE), result)
    }

    @Test
    fun `a blank host is reported before the user`() {
        val result = SipProbeInputValidator.validate(device(host = "  "))

        assertEquals(SipProbeInput.Missing(SipMissingField.PBX_HOST), result)
    }

    @Test
    fun `a blank user is reported once host and mode are present`() {
        val result = SipProbeInputValidator.validate(device(user = ""))

        assertEquals(SipProbeInput.Missing(SipMissingField.SIP_USER), result)
    }

    @Test
    fun `a complete registrar device resolves with its typed port and password`() {
        val result = SipProbeInputValidator.validate(device(host = "pbx.local", port = 5061, user = "100", password = "secret"))

        assertEquals(SipProbeInput.Ready(host = "pbx.local", port = 5061, user = "100", password = "secret"), result)
    }

    @Test
    fun `a missing port falls back to the shared default`() {
        val result = SipProbeInputValidator.validate(device(port = null))

        assertEquals(
            SipProbeInput.Ready(host = "pbx.local", port = SipCallTiming.DEFAULT_SIP_PORT, user = "100", password = "secret"),
            result,
        )
    }

    @Test
    fun `a null password probes with an empty one rather than failing`() {
        val result = SipProbeInputValidator.validate(device(password = null))

        assertEquals(SipProbeInput.Ready(host = "pbx.local", port = 5061, user = "100", password = ""), result)
    }

    // --- registration answer mapping --------------------------------------

    @Test
    fun `an accepted registration reports the server`() {
        val outcome = SipProbeOutcomeMapper.toOutcome(SipRegistrationEvent.Ok, stateName = "Ok", message = "", server = "pbx.local:5061")

        assertEquals(SipProbeResult.Registered("pbx.local:5061"), outcome)
    }

    @Test
    fun `a failed and a cleared registration are both refusals carrying the state name`() {
        assertEquals(
            SipProbeResult.Rejected("Failed", "bad password"),
            SipProbeOutcomeMapper.toOutcome(SipRegistrationEvent.Failed, "Failed", "bad password", "s"),
        )
        assertEquals(
            SipProbeResult.Rejected("Cleared", "expired"),
            SipProbeOutcomeMapper.toOutcome(SipRegistrationEvent.Cleared, "Cleared", "expired", "s"),
        )
    }

    @Test
    fun `a refusal with no message falls back to the state name`() {
        val outcome = SipProbeOutcomeMapper.toOutcome(SipRegistrationEvent.Failed, stateName = "Failed", message = "", server = "s")

        assertEquals(SipProbeResult.Rejected("Failed", "Failed"), outcome)
    }

    @Test
    fun `progress and unknown answers are not final`() {
        assertNull(SipProbeOutcomeMapper.toOutcome(SipRegistrationEvent.Other, stateName = "Progress", message = "", server = "s"))
    }
}
