package io.github.mvolkert.entryrecorder.util

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
import io.github.mvolkert.entryrecorder.testing.testCredentialCipher
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the trigger-export decisions [ExportTriggerRunner] owns now that they no longer sit inside a
 * [io.github.mvolkert.entryrecorder.worker.ExportTriggerWorker]: the folder gate, the latest-vs-all scope,
 * the missing-file skip, the lazy transcode with its raw fallback, the SAF save counting, and the
 * transient-error retry — plus the single-recording entry the auto-transcode mirror queues
 * (`add-auto-transcode-export`), whose explicit transcode override must not read `transcodeOnExport`, and
 * which of its outcomes reach [ExportFailureMonitor]: a run that lands nothing does, a degraded delivery
 * that still landed does not.
 * Runs the real repository over the in-memory fake DAOs and the hand-written export
 * fakes, so no WorkManager, `Context` or ContentProvider is involved.
 */
class ExportTriggerRunnerTest {

    private val deviceDao = FakeDeviceDao()
    private val recordingDao = FakeRecordingDao()
    private val settingsDao = FakeAppSettingsDao()
    private val dispatcher = StandardTestDispatcher()
    private val repository =
        IntercomRepository(deviceDao, recordingDao, settingsDao, FakeActiveServerRecordingDao(), testCredentialCipher(), dispatcher)
    private val gateway = FakeExportGateway()
    private val transcoder = FakeH264Transcoder()
    private val monitor = ExportFailureMonitor()
    private val runner = ExportTriggerRunner(repository, gateway, transcoder, monitor, dispatcher)

    /** A real temp file so the runner's `source.exists()` gate passes and a payload can be delivered. */
    private fun existingFile(name: String): File =
        File.createTempFile("exp-", ".mkv").apply { writeText("v"); deleteOnExit() }

    private suspend fun configure(folder: String, transcode: Boolean = false) =
        repository.updateSettings(AppSettings(exportFolderUri = folder, transcodeOnExport = transcode))

    private suspend fun insert(id: Long, filePath: String, timestamp: Long = 1_000L + id) {
        repository.insertRecording(
            Recording(
                id = id,
                deviceId = 1L,
                deviceName = "Doorbell",
                eventType = EventType.MOTION,
                timestamp = timestamp,
                filePath = filePath,
            )
        )
    }

    @Test
    fun `blank folder fails without touching the gateway`() = runTest(dispatcher) {
        configure(folder = "")
        insert(1L, existingFile("a").absolutePath)

        val outcome = runner.run(exportAll = false)

        assertEquals(ExportTriggerOutcome.Failure("No export folder configured"), outcome)
        assertTrue(gateway.safSaveCalls.isEmpty())
        assertNotNull("a mirror that cannot be written is surfaced, not only logged", monitor.failure.value)
    }

    @Test
    fun `no recordings is an empty success`() = runTest(dispatcher) {
        configure(folder = "content://export")

        val outcome = runner.run(exportAll = true)

        assertEquals(ExportTriggerOutcome.Empty("No recordings"), outcome)
    }

    @Test
    fun `latest scope exports only the most recent recording`() = runTest(dispatcher) {
        configure(folder = "content://export")
        val older = existingFile("older")
        val newer = existingFile("newer")
        insert(1L, older.absolutePath, timestamp = 1_000L)
        insert(2L, newer.absolutePath, timestamp = 2_000L)

        val outcome = runner.run(exportAll = false)

        assertEquals(ExportTriggerOutcome.Done(exported = 1, total = 1), outcome)
        assertEquals(listOf(newer.absolutePath), gateway.safSaveCalls.map { it.second.absolutePath })
    }

    @Test
    fun `all scope exports every existing recording`() = runTest(dispatcher) {
        configure(folder = "content://export")
        insert(1L, existingFile("a").absolutePath)
        insert(2L, existingFile("b").absolutePath)

        val outcome = runner.run(exportAll = true)

        assertEquals(ExportTriggerOutcome.Done(exported = 2, total = 2), outcome)
        assertEquals(2, gateway.safSaveCalls.size)
    }

    @Test
    fun `a recording whose file is missing is skipped and not counted`() = runTest(dispatcher) {
        configure(folder = "content://export")
        insert(1L, existingFile("present").absolutePath)
        insert(2L, "/no/such/gone.mkv")

        val outcome = runner.run(exportAll = true)

        assertEquals(ExportTriggerOutcome.Done(exported = 1, total = 2), outcome)
        assertEquals(1, gateway.safSaveCalls.size)
    }

    @Test
    fun `transcode on delivers the encoded file`() = runTest(dispatcher) {
        configure(folder = "content://export", transcode = true)
        insert(1L, existingFile("clip").absolutePath)

        val outcome = runner.run(exportAll = false)

        assertEquals(ExportTriggerOutcome.Done(exported = 1, total = 1), outcome)
        assertEquals(1, transcoder.transcodeCalls.size)
        assertTrue(
            "the delivered payload must be the transcoded file, not the source",
            gateway.safSaveCalls.single().second.name.startsWith("fake_h264_"),
        )
    }

    @Test
    fun `transcode failure falls back to the raw source instead of aborting`() = runTest(dispatcher) {
        configure(folder = "content://export", transcode = true)
        val source = existingFile("clip")
        insert(1L, source.absolutePath)
        transcoder.transcodeFailure = IllegalStateException("encoder died")

        val outcome = runner.run(exportAll = false)

        assertEquals(ExportTriggerOutcome.Done(exported = 1, total = 1, degraded = 1), outcome)
        assertEquals(1, transcoder.transcodeCalls.size)
        assertEquals(source.absolutePath, gateway.safSaveCalls.single().second.absolutePath)
    }

    @Test
    fun `a refused SAF save is not counted`() = runTest(dispatcher) {
        configure(folder = "content://export")
        gateway.safSaveResult = false
        insert(1L, existingFile("clip").absolutePath)

        val outcome = runner.run(exportAll = false)

        assertEquals(ExportTriggerOutcome.Done(exported = 0, total = 1), outcome)
        assertNotNull("a failed write must set the retained export failure", monitor.failure.value)
    }

    @Test
    fun `a successful SAF save clears the retained export failure`() = runTest(dispatcher) {
        configure(folder = "content://export")
        monitor.recordFailure("prior stall")
        gateway.safSaveResult = true
        insert(1L, existingFile("clip").absolutePath)

        runner.run(exportAll = false)

        assertNull("a landing write clears the stall", monitor.failure.value)
    }

    @Test
    fun `a transient write error asks for a retry`() = runTest(dispatcher) {
        configure(folder = "content://export")
        insert(1L, existingFile("clip").absolutePath)
        gateway.saveFailure = RuntimeException("provider unavailable")

        val outcome = runner.run(exportAll = false)

        assertEquals(ExportTriggerOutcome.Retry, outcome)
        assertNotNull("a transient export error is surfaced while WorkManager retries", monitor.failure.value)
    }

    @Test
    fun `a transient auto-export error is surfaced`() = runTest(dispatcher) {
        configure(folder = "content://export")
        insert(1L, existingFile("clip").absolutePath)
        gateway.saveFailure = RuntimeException("provider unavailable")

        val outcome = runner.runOne(recordingId = 1L, transcode = true)

        assertEquals(ExportTriggerOutcome.Retry, outcome)
        assertNotNull(monitor.failure.value)
    }

    @Test
    fun `a degraded delivery clears the stall rather than raising one`() = runTest(dispatcher) {
        // The clip is in the folder, so the archive has no hole: the degradation belongs in the outcome and the
        // log, and an earlier stall notice is withdrawn by the write that did land.
        configure(folder = "content://export")
        monitor.recordFailure("prior stall")
        insert(1L, existingFile("clip").absolutePath)
        transcoder.transcodeFailure = IllegalStateException("encoder died")

        val outcome = runner.runOne(recordingId = 1L, transcode = true)

        assertEquals(ExportTriggerOutcome.Done(exported = 1, total = 1, degraded = 1), outcome)
        assertNull("a delivered clip is not a stalled export", monitor.failure.value)
    }

    @Test
    fun `runOne transcodes the target even with the manual transcode switch off`() = runTest(dispatcher) {
        // The auto-transcode mirror carries its own mode: what in-app export does is none of its business.
        configure(folder = "content://export", transcode = false)
        val target = existingFile("target")
        insert(1L, target.absolutePath)
        insert(2L, existingFile("other").absolutePath)

        val outcome = runner.runOne(recordingId = 1L, transcode = true)

        assertEquals(ExportTriggerOutcome.Done(exported = 1, total = 1), outcome)
        assertEquals(listOf(1L), transcoder.transcodeCalls.map { it.id })
        assertTrue(
            "the delivered payload must be the transcoded file, not the source",
            gateway.safSaveCalls.single().second.name.startsWith("fake_h264_"),
        )
    }

    @Test
    fun `runOne delivers the raw MKV when the encode fails`() = runTest(dispatcher) {
        configure(folder = "content://export")
        val source = existingFile("clip")
        insert(1L, source.absolutePath)
        transcoder.transcodeFailure = IllegalStateException("encoder died")

        val outcome = runner.runOne(recordingId = 1L, transcode = true)

        assertEquals("a failed encode still archives the clip, so the mirror has no holes",
            ExportTriggerOutcome.Done(exported = 1, total = 1, degraded = 1), outcome)
        assertEquals(source.absolutePath, gateway.safSaveCalls.single().second.absolutePath)
    }

    @Test
    fun `runOne skips a recording whose file is already gone`() = runTest(dispatcher) {
        configure(folder = "content://export")
        insert(1L, "/no/such/gone.mkv")

        val outcome = runner.runOne(recordingId = 1L, transcode = true)

        assertEquals(ExportTriggerOutcome.Done(exported = 0, total = 1), outcome)
        assertTrue(gateway.safSaveCalls.isEmpty())
        assertNotNull("an opted-in mirror that delivered nothing is surfaced", monitor.failure.value)
    }

    @Test
    fun `runOne of a deleted recording row is an empty success`() = runTest(dispatcher) {
        configure(folder = "content://export")

        val outcome = runner.runOne(recordingId = 42L, transcode = true)

        assertEquals(ExportTriggerOutcome.Empty("Recording 42 no longer exists"), outcome)
        assertTrue(gateway.safSaveCalls.isEmpty())
        assertNull("a clip the user deleted mid-queue is an empty success, not a stall", monitor.failure.value)
    }

    @Test
    fun `runOne with a blank folder fails before reading the recording`() = runTest(dispatcher) {
        configure(folder = "")
        insert(1L, existingFile("clip").absolutePath)

        val outcome = runner.runOne(recordingId = 1L, transcode = true)

        assertEquals(ExportTriggerOutcome.Failure("No export folder configured"), outcome)
        assertTrue(gateway.safSaveCalls.isEmpty())
        assertTrue(transcoder.transcodeCalls.isEmpty())
        assertNotNull(monitor.failure.value)
    }
}
