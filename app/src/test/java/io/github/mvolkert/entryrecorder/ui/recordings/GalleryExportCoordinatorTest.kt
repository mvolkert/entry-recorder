@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.mvolkert.entryrecorder.ui.recordings

import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.testing.FakeUiStrings
import io.github.mvolkert.entryrecorder.testing.assertNone
import io.github.mvolkert.entryrecorder.testing.assertEquals
import io.github.mvolkert.entryrecorder.testing.expectIs
import io.github.mvolkert.entryrecorder.testing.assertFalse
import io.github.mvolkert.entryrecorder.testing.assertTrue
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The run-state rules [GalleryExportCoordinator] exists to own: a second request during a run is refused
 * without disturbing the strip the first run is drawing, and the run that ends is the one that clears it.
 *
 * Device-free by construction — a [StandardTestDispatcher], [FakeUiStrings] and lambdas for the two sinks.
 */
class GalleryExportCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()

    private class Recorder {
        val messages = mutableListOf<Pair<String, Boolean>>()
        val events = mutableListOf<RecordingsUiEvent>()
    }

    private fun coordinator(strings: FakeUiStrings, recorder: Recorder) = GalleryExportCoordinator(
        scope = CoroutineScope(dispatcher),
        strings = strings,
        emitMessage = { text, short -> recorder.messages += text to short },
        emitEvent = { recorder.events += it },
    )

    @Test
    fun `a second request during a run is refused and leaves the running strip alone`() = runTest(dispatcher) {
        val strings = FakeUiStrings()
        val recorder = Recorder()
        val subject = coordinator(strings, recorder)
        val holdOpen = CompletableDeferred<Unit>()

        assertTrue(
            subject.start { ctx ->
                ctx.onExportProgress(40, R.string.recordings_export_progress_body)
                ctx.onBatchProgress(1, 3)
                ctx.onServerDownload(55)
                holdOpen.await()
            },
            "the first request must be accepted",
        )
        advanceUntilIdle()
        assertEquals(40, expectIs<ExportRunProgress>(subject.exportProgress.value).percent)
        assertTrue(subject.busy)

        assertFalse(subject.start { }, "a rival run must not start over the shared flows")
        assertEquals(strings(R.string.recordings_run_busy), recorder.messages.single().first)
        advanceUntilIdle()
        assertEquals(40, expectIs<ExportRunProgress>(subject.exportProgress.value).percent)
        assertEquals(1 to 3, subject.batchProgress.value)
        assertEquals(55, subject.serverDownload.value, "the refused request must not clear the strip")
        assertEquals(0, recorder.events.size, "the refusal produces no one-shot event")

        holdOpen.complete(Unit)
        advanceUntilIdle()
        assertNone(subject.exportProgress.value, "the finished run must leave no progress behind")
        assertNone(subject.batchProgress.value)
        assertNone(subject.serverDownload.value)
        assertFalse(subject.busy)
    }

    @Test
    fun `cancelling the run drops the strip and says so`() = runTest(dispatcher) {
        val strings = FakeUiStrings()
        val recorder = Recorder()
        val subject = coordinator(strings, recorder)
        val holdOpen = CompletableDeferred<Unit>()

        subject.start { ctx ->
            ctx.onExportProgress(10, R.string.recordings_export_progress_body)
            holdOpen.await()
        }
        advanceUntilIdle()

        subject.cancel()
        advanceUntilIdle()

        assertNone(subject.exportProgress.value, "a cancelled run must clear the strip")
        assertFalse(subject.busy)
        assertTrue(strings.emittedResource(R.string.recordings_run_cancelled))
    }

    @Test
    fun `the runner's share and celebrate sinks reach the event channel`() = runTest(dispatcher) {
        val strings = FakeUiStrings()
        val recorder = Recorder()
        val subject = coordinator(strings, recorder)
        val file = File("clip.mkv")

        subject.start { ctx ->
            ctx.onShare(listOf(file), null)
            ctx.onCelebrate()
        }
        advanceUntilIdle()

        val share = expectIs<RecordingsUiEvent.Share>(recorder.events[0])
        assertEquals(listOf(file), share.files)
        expectIs<RecordingsUiEvent.ExportCompleted>(recorder.events[1])
        assertNone(subject.exportProgress.value)
    }
}
