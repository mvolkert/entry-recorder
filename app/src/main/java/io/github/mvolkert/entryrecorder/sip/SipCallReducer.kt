package io.github.mvolkert.entryrecorder.sip

/**
 * The transitions a live call can make, in the app's own vocabulary.
 *
 * The Linphone `Call.State` values are collapsed into these so the state machine below has no native type to
 * read; the single place that translates a `Call.State` into a `SipCallEvent` is [SipCallMapper].
 */
enum class SipCallEvent {
    Incoming,
    Connected,
    Ended,
    Error,
}

/**
 * What the UI layer must do in response to a transition, kept out of the reducer so the reducer stays a pure
 * function of (state, event). The manager interprets these against Linphone and `AudioManager`.
 */
enum class SipCallEffect {
    /** Report the ring to the monitor service ([SipCallManager.onIncomingCall]). */
    ReportIncoming,

    /** Push audio to the speaker for the connected call. */
    RouteAudioToSpeaker,

    /** Arm the bounded ENDED/ERROR -> IDLE window after a terminal state. */
    ScheduleIdleReset,

    /** Forget the active call handle. */
    ClearCurrentCall,
}

/** The next session state plus the side effects the caller has to carry out. */
data class SipReducerResult(
    val state: SipSessionState,
    val effects: Set<SipCallEffect>,
)

/**
 * The call-screen state machine, isolated from the Linphone listener that feeds it.
 *
 * Extracted from [SipCallManager] so the exact transitions the call screen depends on are ordinary code with
 * no native `Call` to drive them. Two behaviours that used to be regressions and are now pinned by this single
 * definition: a terminal [SipCallEvent.Ended] / [SipCallEvent.Error] writes a **fresh** state so it stays
 * observable for the bounded idle window instead of being overwritten to IDLE in the same block, and both arm
 * [SipCallEffect.ScheduleIdleReset]; only an incoming call fires [SipCallEffect.ReportIncoming], and only a
 * connect routes audio.
 */
object SipCallReducer {
    /**
     * Reduces [current] under [event]. [errorMessage] (an error reason) and [rawCallState] (Linphone's own
     * state string, shown in debug) are supplied by the caller so the reducer never touches a native type.
     */
    fun reduce(
        current: SipSessionState,
        event: SipCallEvent,
        errorMessage: String?,
        rawCallState: String?,
    ): SipReducerResult = when (event) {
        SipCallEvent.Incoming -> SipReducerResult(
            state = SipSessionState(state = CallUiState.RINGING_INCOMING, rawCallState = rawCallState),
            effects = setOf(SipCallEffect.ReportIncoming),
        )
        SipCallEvent.Connected -> SipReducerResult(
            state = current.copy(
                state = CallUiState.CONNECTED,
                errorMessage = null,
                rawCallState = rawCallState,
            ),
            effects = setOf(SipCallEffect.RouteAudioToSpeaker),
        )
        SipCallEvent.Ended -> SipReducerResult(
            state = SipSessionState(state = CallUiState.ENDED, rawCallState = rawCallState),
            effects = setOf(SipCallEffect.ClearCurrentCall, SipCallEffect.ScheduleIdleReset),
        )
        SipCallEvent.Error -> SipReducerResult(
            state = SipSessionState(
                state = CallUiState.ERROR,
                errorMessage = errorMessage,
                rawCallState = rawCallState,
            ),
            effects = setOf(SipCallEffect.ClearCurrentCall, SipCallEffect.ScheduleIdleReset),
        )
    }
}
