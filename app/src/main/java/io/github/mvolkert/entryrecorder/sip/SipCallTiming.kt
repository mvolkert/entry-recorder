package io.github.mvolkert.entryrecorder.sip

/**
 * The two timers around a finished call, in one place because they are coupled.
 *
 * The call screen dismisses itself while the terminal state is still readable from
 * [SipCallManager.sessionState]; living in separate files, the pair silently lost that relationship
 * (shortening the idle window would have stopped the auto-dismiss firing at all).
 */
object SipCallTiming {
    /** How long [CallUiState.ENDED] / [CallUiState.ERROR] stay observable before the session drops to IDLE. */
    const val POST_CALL_IDLE_MS = 2_500L

    /** Headroom kept before the dismiss, so the "call ended" line is always visible for a moment. */
    private const val DISMISS_HEADROOM_MS = 1_000L

    /** Derived rather than written down twice: the dismiss can no longer fall outside the idle window. */
    const val TERMINAL_CALL_DISMISS_MS = POST_CALL_IDLE_MS - DISMISS_HEADROOM_MS
}
