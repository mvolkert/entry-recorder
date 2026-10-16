package io.github.mvolkert.entryrecorder.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the call-screen state machine [SipCallReducer.reduce] drives now that it no longer lives inside the
 * Linphone listener. It pins the transitions the call screen depends on and the two regressions the reducer
 * exists to prevent: a terminal state stays observable (fresh state, never collapsed to IDLE in the same
 * step) and arms the idle reset, while only an incoming call reports a ring and only a connect routes audio.
 */
class SipCallReducerTest {

    private val idle = SipSessionState(state = CallUiState.IDLE)

    private fun reduce(
        current: SipSessionState,
        event: SipCallEvent,
        errorMessage: String? = null,
        rawCallState: String? = "raw",
    ) = SipCallReducer.reduce(current, event, errorMessage, rawCallState)

    @Test
    fun `incoming rings and reports the call without touching audio`() {
        val result = reduce(idle, SipCallEvent.Incoming)

        assertEquals(CallUiState.RINGING_INCOMING, result.state.state)
        assertNull("a fresh incoming session carries no error", result.state.errorMessage)
        assertEquals(setOf(SipCallEffect.ReportIncoming), result.effects)
    }

    @Test
    fun `connect clears any prior error, keeps mute and speaker flags, and routes audio`() {
        val ringing = SipSessionState(
            state = CallUiState.RINGING_INCOMING,
            isMicMuted = true,
            isSpeakerOn = false,
        )

        val result = reduce(ringing, SipCallEvent.Connected, errorMessage = null)

        assertEquals(CallUiState.CONNECTED, result.state.state)
        assertTrue("connected preserves the caller's mute state", result.state.isMicMuted)
        assertFalse("connected preserves the caller's speaker state", result.state.isSpeakerOn)
        assertEquals(setOf(SipCallEffect.RouteAudioToSpeaker), result.effects)
    }

    @Test
    fun `connect drops a stale error message`() {
        val errored = SipSessionState(state = CallUiState.ERROR, errorMessage = "boom")

        val result = reduce(errored, SipCallEvent.Connected)

        assertEquals(CallUiState.CONNECTED, result.state.state)
        assertNull(result.state.errorMessage)
    }

    @Test
    fun `end stays observable as ENDED rather than collapsing to idle`() {
        val connected = SipSessionState(state = CallUiState.CONNECTED)

        val result = reduce(connected, SipCallEvent.Ended)

        assertEquals(CallUiState.ENDED, result.state.state)
        assertEquals(setOf(SipCallEffect.ClearCurrentCall, SipCallEffect.ScheduleIdleReset), result.effects)
    }

    @Test
    fun `error surfaces its reason and arms the idle reset`() {
        val connected = SipSessionState(state = CallUiState.CONNECTED)

        val result = reduce(connected, SipCallEvent.Error, errorMessage = "403 Forbidden")

        assertEquals(CallUiState.ERROR, result.state.state)
        assertEquals("403 Forbidden", result.state.errorMessage)
        assertEquals(setOf(SipCallEffect.ClearCurrentCall, SipCallEffect.ScheduleIdleReset), result.effects)
    }

    @Test
    fun `a terminal state does not inherit the previous mute or speaker flags`() {
        // The original wrote a fresh SipSessionState on ENDED/ERROR, resetting mic and speaker defaults;
        // the reducer must keep that so the next call screen starts from a clean slate.
        val muted = SipSessionState(state = CallUiState.CONNECTED, isMicMuted = true, isSpeakerOn = false)

        val ended = reduce(muted, SipCallEvent.Ended)

        assertFalse(ended.state.isMicMuted)
        assertTrue(ended.state.isSpeakerOn)
    }
}
