package io.github.mvolkert.entryrecorder.util

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one retained export-write failure the whole process agrees on.
 *
 * An auto-export mirror that fails while the app is backgrounded used to be only a `Log.e`, so the SAF
 * archive silently stopped filling and nobody found out until they needed a file that was never copied.
 * The write paths ([io.github.mvolkert.entryrecorder.video.LocalCaptureLoop]'s mirror,
 * [io.github.mvolkert.entryrecorder.worker.ExportTriggerWorker] via [ExportTriggerRunner]) report here —
 * [recordFailure] when a save to the folder does not land, [recordSuccess] when one does — and both the
 * notification observer and the settings hub read [failure], so the condition is visible without opening
 * Settings and clears itself on the next good write.
 *
 * Deliberately free of Android and framework types: it is a plain state box so the failure/clear rule is
 * unit-testable, with the surfacing (notification, UI) kept in the layers that can render it.
 */
@Singleton
class ExportFailureMonitor @Inject constructor() {

    /** A write that did not land. [message] is a short diagnostic reason; the rendered text is a resource. */
    data class Failure(val message: String, val atEpochMs: Long)

    private val _failure = MutableStateFlow<Failure?>(null)

    /** The current unresolved export failure, or null once writes are landing again. */
    val failure: StateFlow<Failure?> = _failure.asStateFlow()

    fun recordFailure(message: String) {
        _failure.value = Failure(message, System.currentTimeMillis())
    }

    fun recordSuccess() {
        _failure.value = null
    }
}
