package io.github.mvolkert.entryrecorder.util

import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.domain.model.Recording
import io.github.mvolkert.entryrecorder.testing.FakeActiveServerRecordingDao
import io.github.mvolkert.entryrecorder.testing.FakeAppSettingsDao
import io.github.mvolkert.entryrecorder.testing.FakeDeviceDao
import io.github.mvolkert.entryrecorder.testing.FakeExportGateway
import io.github.mvolkert.entryrecorder.testing.FakeH264Transcoder
import io.github.mvolkert.entryrecorder.testing.FakeRecordingDao
import io.github.mvolkert.entryrecorder.testing.FakeServerClient
import io.github.mvolkert.entryrecorder.testing.FakeUiStrings
import io.github.mvolkert.entryrecorder.testing.testCredentialCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Drives the whole manual-export pipeline extracted in Phase 3, over the hand-written seams, for every kind and
 * source and the cancellation cleanup: the lazy transcode policy (H.264 only when the Settings toggle is on and
 * the source is a raw MKV, never for RAW_FOLDER), the SHARE / GALLERY / FOLDER delivery, the batch count-down and
 * its null-metadata share, the server download-then-deliver path, and the guard that a cancelled encode drops
 * its half-written file instead of shipping a truncated clip.
 */
class ExportRunnerTest {

    private val deviceDao = FakeDeviceDao()
    private val recordingDao = FakeRecordingDao()
    private val settingsDao = FakeAppSettingsDao()
    private val dispatcher = StandardTestDispatcher()
    private val repository = IntercomRepository(deviceDao, recordingDao, settingsDao, FakeActiveServerRecordingDao(), testCredentialCipher(), dispatcher)

    private val gateway = FakeExportGateway()
    private val transcoder = FakeH264Transcoder()
    private val server = FakeServerClient()
    private val strings = FakeUiStrings()
    private val scratch = Files.createTempDirectory("export-scratch-").toFile()
    private val runner = ExportRunner(
        repository = repository,
        serverClient = server,
        exportGateway = gateway,
        transcoder = transcoder,
        storageDirs = AppStorageDirs(scratch, scratch, scratch),
        strings = strings,
        ioDispatcher = dispatcher,
    )

    private class Capture {
        val shares = mutableListOf<Pair<List<File>, ExportMetadata?>>()
        val messages = mutableListOf<Pair<String, Boolean>>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val batch = mutableListOf<Pair<Int, Int>>()
        val downloads = mutableListOf<Int>()
        var celebrated = 0
    }

    private fun capture(): Pair<ExportContext, Capture> {
        val c = Capture()
        val ctx = ExportContext(
            onExportProgress = { p, body -> c.progress += p to body },
            onBatchProgress = { d, t -> c.batch += d to t },
            onServerDownload = { p -> c.downloads += p },
            onMessage = { text, short -> c.messages += text to short },
            onShare = { files, metadata -> c.shares += files to metadata },
            onCelebrate = { c.celebrated++ },
        )
        return ctx to c
    }

    private suspend fun configure(transcode: Boolean, folder: String = "") =
        repository.updateSettings(AppSettings(transcodeOnExport = transcode, exportFolderUri = folder))

    private fun recording(ext: String = "mkv", id: Long = 1L, name: String = "Doorbell", type: EventType = EventType.MOTION) =
        Recording(
            id = id,
            deviceId = 1L,
            deviceName = name,
            eventType = type,
            filePath = File(scratch, "clip_$id.$ext").absolutePath,
        )

    private fun tempFile(path: String): File = File(path).apply { writeText("bytes") }

    @Test
    fun `share without transcode ships the original and carries metadata`() = runTest(dispatcher) {
        configure(transcode = false)
        val rec = recording(ext = "mkv")
        val (ctx, c) = capture()

        runner.exportLocal(rec, RecordingExportKind.SHARE, ctx)

        assertEquals(1, c.shares.size)
        assertEquals(listOf(File(rec.filePath)), c.shares[0].first)
        assertEquals(EventType.MOTION, c.shares[0].second?.eventType)
        assertTrue(transcoder.transcodeCalls.isEmpty())
    }

    @Test
    fun `share with transcode re-encodes the mkv and drives the progress strip`() = runTest(dispatcher) {
        configure(transcode = true)
        val rec = recording(ext = "mkv")
        val (ctx, c) = capture()

        runner.exportLocal(rec, RecordingExportKind.SHARE, ctx)

        assertEquals(1, transcoder.transcodeCalls.size)
        assertEquals(1, c.shares.size)
        assertTrue(c.shares[0].first.single().name.startsWith("fake_h264_"))
        assertTrue(c.progress.any { it.second == R.string.recordings_export_progress_body })
    }

    @Test
    fun `non-mkv source is shared as-is even with the transcode toggle on`() = runTest(dispatcher) {
        configure(transcode = true)
        val rec = recording(ext = "mp4")
        val (ctx, c) = capture()

        runner.exportLocal(rec, RecordingExportKind.SHARE, ctx)

        assertTrue(transcoder.transcodeCalls.isEmpty())
        assertEquals(listOf(File(rec.filePath)), c.shares[0].first)
    }

    @Test
    fun `gallery save celebrates once and reports the success line`() = runTest(dispatcher) {
        configure(transcode = false)
        val rec = recording(ext = "mp4")
        val (ctx, c) = capture()

        runner.exportLocal(rec, RecordingExportKind.GALLERY, ctx)

        assertEquals(1, c.celebrated)
        assertTrue(strings.emittedResource(R.string.export_saved_gallery))
    }

    @Test
    fun `folder export with no folder chosen asks for one and delivers nothing`() = runTest(dispatcher) {
        configure(transcode = false, folder = "")
        val rec = recording(ext = "mkv")
        val (ctx, c) = capture()

        runner.exportLocal(rec, RecordingExportKind.FOLDER, ctx)

        assertTrue(strings.emittedResource(R.string.recordings_toast_set_folder_first))
        assertTrue(gateway.safSaveCalls.isEmpty())
        assertEquals(0, c.celebrated)
    }

    @Test
    fun `folder export writes one file and celebrates`() = runTest(dispatcher) {
        configure(transcode = false, folder = "content://saf/export")
        val rec = recording(ext = "mkv")
        tempFile(rec.filePath)
        val (ctx, c) = capture()

        runner.exportLocal(rec, RecordingExportKind.FOLDER, ctx)

        assertEquals(1, gateway.safSaveCalls.size)
        assertTrue(strings.emittedResource(R.string.recordings_toast_exported_to))
        assertEquals(1, c.celebrated)
    }

    @Test
    fun `raw folder ships the original even when transcode is on`() = runTest(dispatcher) {
        configure(transcode = true, folder = "content://saf/export")
        val rec = recording(ext = "mkv")
        tempFile(rec.filePath)
        val (ctx, c) = capture()

        runner.exportLocal(rec, RecordingExportKind.RAW_FOLDER, ctx)

        assertTrue(transcoder.transcodeCalls.isEmpty())
        assertEquals(1, gateway.safSaveCalls.size)
        assertEquals(File(rec.filePath), gateway.safSaveCalls[0].second)
        assertEquals(1, c.celebrated)
    }

    @Test
    fun `cancelled transcode drops the partial encode and rethrows`() = runTest(dispatcher) {
        configure(transcode = true)
        val rec = recording(ext = "mkv")
        val src = tempFile(rec.filePath)
        val partial = transcoder.outputFor(src).apply { writeText("half-written") }
        transcoder.blockTranscode = CompletableDeferred()
        val (ctx, c) = capture()

        var cancelled = false
        val job = launch {
            try {
                runner.exportLocal(rec, RecordingExportKind.SHARE, ctx)
            } catch (e: CancellationException) {
                cancelled = true
            }
        }
        advanceUntilIdle()
        job.cancel()
        job.join()

        assertTrue("the run must observe cancellation", cancelled)
        assertFalse("the interrupted encode is a discard, not a deliverable file", partial.exists())
        assertTrue(c.shares.isEmpty())
    }

    @Test
    fun `batch share collects every file into one metadata-less sheet`() = runTest(dispatcher) {
        configure(transcode = false)
        val targets = listOf(recording(ext = "mp4", id = 1), recording(ext = "mp4", id = 2))
        val (ctx, c) = capture()

        runner.exportBatch(targets, RecordingExportKind.SHARE, ctx)

        assertEquals(1, c.shares.size)
        assertEquals(2, c.shares[0].first.size)
        assertNull(c.shares[0].second)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), c.batch)
    }

    @Test
    fun `empty batch asks to select something`() = runTest(dispatcher) {
        configure(transcode = false)
        val (ctx, c) = capture()

        runner.exportBatch(emptyList(), RecordingExportKind.SHARE, ctx)

        assertTrue(strings.emittedResource(R.string.recordings_toast_nothing_selected))
        assertTrue(c.shares.isEmpty())
    }

    @Test
    fun `server row downloads then delivers to share`() = runTest(dispatcher) {
        configure(transcode = false)
        val source = ServerRecordingSource(
            id = 9L,
            videoAbsoluteUrl = "https://srv/media/9.mp4?api_key=k",
            deviceName = "Gate",
            eventType = EventType.RING,
        )
        server.downloadResult = Result.success(File("downloaded.mp4"))
        val (ctx, c) = capture()

        runner.exportServer(source, RecordingExportKind.SHARE, ctx)

        assertEquals(listOf(source.videoAbsoluteUrl), server.downloadCalls)
        assertTrue(c.downloads.isNotEmpty())
        assertEquals(1, c.shares.size)
        assertEquals(EventType.RING, c.shares[0].second?.eventType)
        assertEquals("Gate", c.shares[0].second?.deviceName)
    }

    @Test
    fun `failed server download reports the failure and shares nothing`() = runTest(dispatcher) {
        configure(transcode = false)
        val source = ServerRecordingSource(9L, "https://srv/media/9.mp4", "Gate", EventType.RING)
        server.downloadResult = Result.failure(IOException("offline"))
        val (ctx, c) = capture()

        runner.exportServer(source, RecordingExportKind.SHARE, ctx)

        assertTrue(strings.emittedResource(R.string.recordings_toast_download_failed))
        assertTrue(c.shares.isEmpty())
    }
}
