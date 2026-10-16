package io.github.mvolkert.entryrecorder.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.core.Call
import org.linphone.core.RegistrationState

/**
 * Covers [SipCallMapper], the single place this feature reads a native Linphone enum.
 *
 * These assertions only touch the `Call.State` / `RegistrationState` enum constants and the `when` lookup
 * over them — no `Core`, no `Factory`, no native call — so the translation from the SDK's vocabulary into the
 * app's own [SipCallEvent] / [SipRegistrationEvent] is verifiable on the JVM without a device. It pins that
 * the values the listener used to branch on are mapped identically, that `IncomingEarlyMedia` is
 * deliberately **not** a UI event (only [SipCallMapper.shouldAccept] treats it as answerable), and that an
 * absent `RegistrationState` is "no answer yet" rather than a refusal.
 */
class SipCallMapperTest {

    @Test
    fun `call states collapse to the app's events`() {
        assertEquals(SipCallEvent.Incoming, SipCallMapper.toEvent(Call.State.IncomingReceived))
        assertEquals(SipCallEvent.Connected, SipCallMapper.toEvent(Call.State.StreamsRunning))
        assertEquals(SipCallEvent.Connected, SipCallMapper.toEvent(Call.State.Connected))
        assertEquals(SipCallEvent.Ended, SipCallMapper.toEvent(Call.State.End))
        assertEquals(SipCallEvent.Ended, SipCallMapper.toEvent(Call.State.Released))
        assertEquals(SipCallEvent.Error, SipCallMapper.toEvent(Call.State.Error))
    }

    @Test
    fun `a state with no UI meaning maps to no event`() {
        // IncomingEarlyMedia is answerable but is not itself a transition the call screen reacts to; the
        // original listener fell through to `else -> {}` for it, so the reducer must not see an event.
        assertNull(SipCallMapper.toEvent(Call.State.IncomingEarlyMedia))
        assertNull(SipCallMapper.toEvent(null))
    }

    @Test
    fun `accept is allowed for any pre-answer incoming state only`() {
        assertTrue(SipCallMapper.shouldAccept(Call.State.IncomingReceived))
        assertTrue(SipCallMapper.shouldAccept(Call.State.IncomingEarlyMedia))
        assertFalse(SipCallMapper.shouldAccept(Call.State.Connected))
        assertFalse(SipCallMapper.shouldAccept(Call.State.Error))
        assertFalse(SipCallMapper.shouldAccept(null))
    }

    @Test
    fun `registration answers collapse, with absent treated as not final`() {
        assertEquals(SipRegistrationEvent.Ok, SipCallMapper.registrationEventOf(RegistrationState.Ok))
        assertEquals(SipRegistrationEvent.Failed, SipCallMapper.registrationEventOf(RegistrationState.Failed))
        assertEquals(SipRegistrationEvent.Cleared, SipCallMapper.registrationEventOf(RegistrationState.Cleared))
        assertEquals(SipRegistrationEvent.Other, SipCallMapper.registrationEventOf(null))
    }
}
