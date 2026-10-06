package io.github.mvolkert.entryrecorder.sip

/**
 * The timers around a finished call, in one place because they are coupled.
 *
 * The call screen dismisses itself while the terminal state is still readable from
 * [SipCallManager.sessionState]; living in separate files, the pair silently lost that relationship
 * (shortening the idle window would have stopped the auto-dismiss firing at all).
 * The registration-probe numbers live here too, being the other SIP timeouts this app owns.
 */
object SipCallTiming {
    /** How long [CallUiState.ENDED] / [CallUiState.ERROR] stay observable before the session drops to IDLE. */
    const val POST_CALL_IDLE_MS = 2_500L

    /** Headroom kept before the dismiss, so the "call ended" line is always visible for a moment. */
    private const val DISMISS_HEADROOM_MS = 1_000L

    /** Derived rather than written down twice: the dismiss can no longer fall outside the idle window. */
    const val TERMINAL_CALL_DISMISS_MS = POST_CALL_IDLE_MS - DISMISS_HEADROOM_MS

    /** How long a registration probe waits for the registrar before reporting no answer. */
    const val SIP_PROBE_TIMEOUT_MS = 10_000L

    /**
     * Local SIP ports a probe core may use, kept off 5060 so a probe never collides with the monitor's core.
     *
     * Fixed rather than OS-assigned because a refused bind is silent and port 0 is not portable across
     * liblinphone builds. More than one port because `Core.stop()` hands its socket back only once the wrapper
     * drops the native core, so a second probe can inherit the port the previous one was using and then send
     * nothing at all - a timeout that has nothing to do with the credentials being tested.
     */
    private const val SIP_PROBE_PORT_BASE = 5090
    const val SIP_PROBE_PORT_SLOTS = 4

    /** The local port to give the [slot]-th probe. */
    fun probeLocalPort(slot: Int): Int = SIP_PROBE_PORT_BASE + slot % SIP_PROBE_PORT_SLOTS
}
