package io.github.mvolkert.entryrecorder.data.repository

import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.domain.model.Recording
import io.github.mvolkert.entryrecorder.testing.FakeActiveServerRecordingDao
import io.github.mvolkert.entryrecorder.testing.FakeAppSettingsDao
import io.github.mvolkert.entryrecorder.testing.FakeDeviceDao
import io.github.mvolkert.entryrecorder.testing.FakeRecordingDao
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Exercises the real [IntercomRepository] delete paths over the in-memory fake DAOs — the seam the multi-select
 * and cleanup passes depend on. It pins three contracts: the batch delete drops every listed row in one call
 * (so a clip whose file cannot be reclaimed is still unlisted, never left half-applied), a row is reported as
 * an orphan only when its bytes genuinely survive, and rows outside the batch are untouched.
 */
class IntercomRepositoryDeleteTest {

    private val deviceDao = FakeDeviceDao()
    private val recordingDao = FakeRecordingDao()
    private val settingsDao = FakeAppSettingsDao()
    private val dispatcher = StandardTestDispatcher()
    private val repository = IntercomRepository(deviceDao, recordingDao, settingsDao, FakeActiveServerRecordingDao(), dispatcher)

    private fun recording(id: Long, filePath: String, thumbnailPath: String? = null) = Recording(
        id = id,
        deviceId = 1L,
        deviceName = "Doorbell",
        eventType = EventType.MOTION,
        filePath = filePath,
        thumbnailPath = thumbnailPath,
    )

    /** A directory holding a child file: [File.delete] refuses it, so its bytes survive as an orphan. */
    private fun undeletablePath(): String {
        val dir = Files.createTempDirectory("orphan-").toFile()
        File(dir, "child").writeText("x")
        return dir.absolutePath
    }

    @Test
    fun `batch delete removes every listed row in one call`() = runTest(dispatcher) {
        val paths = listOf("/no/such/a.mkv", "/no/such/b.mkv", "/no/such/c.mkv")
        val recordings = paths.mapIndexed { i, p -> recording(id = (10L + i), filePath = p) }
        recordings.forEach { repository.insertRecording(it) }
        assertEquals(3, recordingDao.rows.size)

        val report = repository.deleteRecordings(recordings)

        assertEquals(3, report.deleted)
        assertTrue("absent files must not be reported as orphans", report.orphanPaths.isEmpty())
        assertTrue(recordingDao.rows.isEmpty())
    }

    @Test
    fun `batch delete unlists a clip whose file survives and reports it as an orphan`() = runTest(dispatcher) {
        val orphan = undeletablePath()
        val clean = recording(id = 1L, filePath = "/no/such/x.mkv")
        val stuck = recording(id = 2L, filePath = orphan)
        repository.insertRecording(clean)
        repository.insertRecording(stuck)

        val report = repository.deleteRecordings(listOf(clean, stuck))

        // The row goes whatever the file does: both are unlisted even though one could not be reclaimed.
        assertTrue(recordingDao.rows.isEmpty())
        assertEquals(2, report.deleted)
        assertEquals(listOf(orphan), report.orphanPaths)
        // Confirm the orphan really is still on disk (the test's premise, not the repository's job).
        assertTrue(File(orphan).exists())
        File(orphan).listFiles()?.forEach { it.delete() }
        File(orphan).delete()
    }

    @Test
    fun `batch delete leaves rows outside the batch untouched`() = runTest(dispatcher) {
        val doomed = recording(id = 1L, filePath = "/no/such/a.mkv")
        val keeper = recording(id = 2L, filePath = "/no/such/b.mkv")
        repository.insertRecording(doomed)
        repository.insertRecording(keeper)

        repository.deleteRecordings(listOf(doomed))

        assertEquals(listOf(2L), recordingDao.rows.map { it.id })
    }

    @Test
    fun `single delete of an absent file reports Deleted and drops the row`() = runTest(dispatcher) {
        val r = recording(id = 1L, filePath = "/no/such/a.mkv")
        repository.insertRecording(r)

        val result = repository.deleteRecording(r)

        assertEquals(RecordingDeleteResult.Deleted, result)
        assertTrue(recordingDao.rows.isEmpty())
    }

    @Test
    fun `single delete of a surviving file reports its orphan path yet still drops the row`() = runTest(dispatcher) {
        val orphan = undeletablePath()
        val r = recording(id = 1L, filePath = orphan)
        repository.insertRecording(r)

        val result = repository.deleteRecording(r)

        assertTrue(result is RecordingDeleteResult.DeletedWithOrphans)
        assertEquals(listOf(orphan), (result as RecordingDeleteResult.DeletedWithOrphans).orphanPaths)
        assertFalse(recordingDao.rows.any { it.id == 1L })
        File(orphan).listFiles()?.forEach { it.delete() }
        File(orphan).delete()
    }

    @Test
    fun `an empty batch delete is a no-op`() = runTest(dispatcher) {
        repository.insertRecording(recording(id = 1L, filePath = "/no/such/a.mkv"))

        val report = repository.deleteRecordings(emptyList())

        assertEquals(0, report.deleted)
        assertEquals(1, recordingDao.rows.size)
    }
}
