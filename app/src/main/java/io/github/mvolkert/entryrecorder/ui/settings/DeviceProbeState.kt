package io.github.mvolkert.entryrecorder.ui.settings

import io.github.mvolkert.entryrecorder.sip.SipProbeResult

/**
 * The states of the two probes the create/edit form can run against values that were typed but never
 * saved. They live in [SettingsViewModel] rather than in the section that shows them, so turning the
 * phone over while a probe is still waiting for the registrar does not restart it and lose the answer.
 *
 * The outcome is carried unrendered on purpose: which message a result maps to is the screen's decision,
 * so the ViewModel stays free of resource ids and of a Context it does not own.
 */
sealed interface DeviceTestState {
    data object Idle : DeviceTestState

    /** A probe is in flight; the button that started it stays disabled while this is the value. */
    data object Running : DeviceTestState

    /** The intercom answered the way its device class accepts. */
    data object Passed : DeviceTestState

    /** It did not, with the exception's own message when it had one. */
    data class Failed(val reason: String?) : DeviceTestState
}

/**
 * The SIP probe's states. A registrar answers in four distinguishable ways, so the outcome is the
 * [SipProbeResult] the manager produced rather than a summary of it — the form has to be able to tell
 * "the registrar refused" apart from "nothing was ever sent", which are bugs on opposite sides of the LAN.
 */
sealed interface SipTestState {
    data object Idle : SipTestState
    data object Running : SipTestState
    data class Completed(val result: SipProbeResult) : SipTestState
}

/**
 * One burst measurement of the snapshot endpoint's real rate. [Measured] carrying `0f` is a result, not a
 * failure: an endpoint that never answered is a wrong path or refused credentials, and the screen says so.
 */
sealed interface SnapshotProbeState {
    data object Idle : SnapshotProbeState
    data object Running : SnapshotProbeState
    data class Measured(val fps: Float) : SnapshotProbeState
}
