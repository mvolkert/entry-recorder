package io.github.mvolkert.entryrecorder.video

import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.testing.FakeAutoExportScheduler
import io.github.mvolkert.entryrecorder.testing.expectIs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the finalize-time mirror branch [LocalCaptureLoop] now delegates to [finalizeExportFor]: which of
 * the two mutually exclusive auto-export modes a finished clip gets, and that a folder has to exist for either
 * to run. Plus that consecutive finishes hand over one export each, in finalize order, none dropped — against
 * a fake scheduler, because the chain those enqueues join is pinned by
 * `io.github.mvolkert.entryrecorder.worker.AutoExportQueueTest` and WorkManager's own serialization is only
 * observable on device (gate 5.3).
 */
class FinalizeExportTest {

    private val folder = "content://export/tree"

    private fun settings(raw: Boolean = false, transcode: Boolean = false, folder: String = this.folder) =
        AppSettings(
            exportFolderUri = folder,
            autoExportOnFinalize = raw,
            autoTranscodeOnFinalize = transcode,
        )

    @Test
    fun `auto-transcode queues the playable mirror instead of copying the raw file`() {
        val decision = finalizeExportFor(settings(transcode = true), recordingId = 7L)

        assertEquals(FinalizeExport.Transcode(recordingId = 7L, rawAlsoRequested = false), decision)
    }

    @Test
    fun `raw auto-export still mirrors the MKV by itself`() {
        assertEquals(FinalizeExport.RawMirror, finalizeExportFor(settings(raw = true), recordingId = 7L))
    }

    @Test
    fun `neither mirror runs without a folder`() {
        // The folder gate comes first: an auto-transcode request with no target would only fail in the worker.
        assertEquals(FinalizeExport.None, finalizeExportFor(settings(transcode = true, folder = ""), 7L))
        assertEquals(FinalizeExport.None, finalizeExportFor(settings(raw = true, folder = "  "), 7L))
        assertEquals(FinalizeExport.None, finalizeExportFor(settings(), 7L))
    }

    @Test
    fun `a row that carries both modes produces one artifact — the transcode, with a warning to log`() {
        // Only reachable from a pre-normalization backup; the branch resolves it instead of writing two files.
        val decision = expectIs<FinalizeExport.Transcode>(
            finalizeExportFor(settings(raw = true, transcode = true), recordingId = 7L),
            "a both-on row still queues the transcode",
        )

        assertTrue("the contradiction has to be visible to the caller", decision.rawAlsoRequested)
        assertEquals(7L, decision.recordingId)
    }

    @Test
    fun `three finishes in a row queue three exports in finalize order`() {
        val scheduler = FakeAutoExportScheduler()
        val stored = settings(transcode = true)

        // What finalizeRecording does per finished clip: decide, then hand the transcode case to the queue.
        listOf(1L, 2L, 3L).forEach { id ->
            when (val export = finalizeExportFor(stored, id)) {
                is FinalizeExport.Transcode -> scheduler.enqueueTranscodeExport(export.recordingId)
                is FinalizeExport.RawMirror, FinalizeExport.None -> Unit
            }
        }

        assertEquals("each finish queues its own export, none dropped or replacing an earlier one",
            listOf(1L, 2L, 3L), scheduler.transcodeQueues)
    }
}
