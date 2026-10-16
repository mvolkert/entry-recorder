package io.github.mvolkert.entryrecorder.sip

import org.linphone.core.Call
import org.linphone.core.RegistrationState

/**
 * The single place this feature reads a native Linphone enum.
 *
 * Kept deliberately thin — a lookup from `Call.State` / `RegistrationState` into the app's own
 * [SipCallEvent] / [SipRegistrationEvent] — so [SipCallReducer] and [SipProbeOutcomeMapper] hold the actual
 * decision logic and stay unit-testable without loading the Linphone native library. Anything unrecognised
 * maps to `null` (no event) or [SipRegistrationEvent.Other] (no answer), matching the `else -> {}` the
 * listener used to carry inline.
 */
object SipCallMapper {
    fun toEvent(state: Call.State?): SipCallEvent? = when (state) {
        Call.State.IncomingReceived -> SipCallEvent.Incoming
        Call.State.StreamsRunning, Call.State.Connected -> SipCallEvent.Connected
        Call.State.End, Call.State.Released -> SipCallEvent.Ended
        Call.State.Error -> SipCallEvent.Error
        else -> null
    }

    /**
     * True for any pre-answer incoming state. Nothing in this app calls `startRinging()`, so today a call
     * stays in IncomingReceived; covering early media too keeps the answer button live if the core advances
     * the call (Linphone 5 has no IncomingRinging).
     */
    fun shouldAccept(state: Call.State?): Boolean =
        state == Call.State.IncomingReceived || state == Call.State.IncomingEarlyMedia

    fun registrationEventOf(state: RegistrationState?): SipRegistrationEvent = when (state) {
        RegistrationState.Ok -> SipRegistrationEvent.Ok
        RegistrationState.Failed -> SipRegistrationEvent.Failed
        // Cleared is where a refused account ends up once its 401/403 retry expires.
        RegistrationState.Cleared -> SipRegistrationEvent.Cleared
        else -> SipRegistrationEvent.Other
    }
}
