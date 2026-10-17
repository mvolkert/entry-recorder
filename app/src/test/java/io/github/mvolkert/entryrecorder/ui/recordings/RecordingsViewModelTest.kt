package io.github.mvolkert.entryrecorder.ui.recordings

import app.cash.turbine.test
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.triggerTypes
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.testing.FakeActiveServerRecordingDao
import io.github.mvolkert.entryrecorder.testing.FakeAppSettingsDao
import io.github.mvolkert.entryrecorder.testing.FakeDeviceDao
import io.github.mvolkert.entryrecorder.testing.FakeExportGateway
import io.github.mvolkert.entryrecorder.testing.FakeH264Transcoder
import io.github.mvolkert.entryrecorder.testing.FakeRecordingDao
import io.github.mvolkert.entryrecorder.testing.FakeServerClient
import io.github.mvolkert.entryrecorder.testing.FakeUiStrings
import io.github.mvolkert.entryrecorder.testing.testCredentialCipher
import io.github.mvolkert.entryrecorder.testing.testDevice
import io.github.mvolkert.entryrecorder.testing.testRecording
import io.github.mvolkert.entryrecorder.testing.assertNone
import io.github.mvolkert.entryrecorder.testing.assertEquals
import io.github.mvolkert.entryrecorder.testing.expectIs
import io.github.mvolkert.entryrecorder.testing.assertFalse
import io.github.mvolkert.entryrecorder.testing.assertTrue
import io.github.mvolkert.entryrecorder.util.AppStorageDirs
import io.github.mvolkert.entryrecorder.util.ExportRunner
import io.github.mvolkert.entryrecorder.util.RecordingExportKind
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Behaviour the export pipeline owes the screen: exactly one run owns the progress strip, a cancelled
 * run leaves no stale progress behind, a folder export actually reaches the gateway, a delete that
 * could not reclaim its file says so, and the three filters narrow the list they render.
 *
 * The repository is the real one over the in-memory fake DAOs; every Android-backed collaborator is a
 * hand-written fake from the testing package.
 */
class RecordingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var deviceDao: FakeDeviceDao
    private lateinit var recordingDao: FakeRecordingDao
    private lateinit var settingsDao: FakeAppSettingsDao
    private lateinit var repository: IntercomRepository
    private lateinit var gateway: FakeExportGateway
    private lateinit var transcoder: FakeH264Transcoder
    private lateinit var strings: FakeUiStrings
    private lateinit var storageDirs: AppStorageDirs
    private lateinit var vm: RecordingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        deviceDao = FakeDeviceDao()
        recordingDao = FakeRecordingDao()
        settingsDao = FakeAppSettingsDao()
        repository = IntercomRepository(deviceDao, recordingDao, settingsDao, FakeActiveServerRecordingDao(), testCredentialCipher(), testDispatcher)
        gateway = FakeExportGateway()
        transcoder = FakeH264Transcoder()
        strings = FakeUiStrings()
        val root = File(System.getProperty("java.io.tmpdir"), "recvm-${System.nanoTime()}")
        storageDirs = AppStorageDirs(
            File(root, "recordings").apply { mkdirs() },
            File(root, "thumbnails").apply { mkdirs() },
            File(root, "export").apply { mkdirs() },
        )
        vm = RecordingsViewModel(
            repository = repository,
            serverClient = FakeServerClient(),
            strings = strings,
            exportRunner = ExportRunner(
                repository = repository,
                serverClient = FakeServerClient(),
                exportGateway = gateway,
                transcoder = transcoder,
                storageDirs = storageDirs,
                strings = strings,
                ioDispatcher = testDispatcher,
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Runs [body] inside runTest on a dispatcher sharing [testDispatcher]'s scheduler — so the
     * ViewModel's viewModelScope and the repository's IO hops queue on one scheduler — then flushes
     * it and leaves. Any collector still running on the test's background scope is cancelled by
     * runTest itself when the body returns, so the test never has to join a live flow.
     */
    private fun runViewModelTest(body: suspend TestScope.() -> Unit) =
        runTest(StandardTestDispatcher(testDispatcher.scheduler)) {
            body()
            advanceUntilIdle()
        }

    /** Seeds the settings row the export paths read, with a folder chosen. */
    private suspend fun seedSettings(exportFolderUri: String = "content://fake/export") {
        repository.updateSettings(repository.getSettings().copy(exportFolderUri = exportFolderUri))
    }

    /**
     * Subscribes to the ViewModel's event channel for the rest of the test on the test's background
     * scope and returns the live list of received events. The collector runs unconfined — a trySend
     * into the buffered channel is delivered immediately, so the list is complete as soon as the
     * scheduler is idle — and background scope means runTest cancels it at the end instead of hanging
     * on the never-completing channel.
     */
    private fun TestScope.collectEvents(): List<RecordingsUiEvent> {
        val received = mutableListOf<RecordingsUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) { vm.events.toList(received) }
        return received
    }

    @Test
    fun `second export while a run is active is refused with the busy message`() = runViewModelTest {
        val src = File.createTempFile("clip", ".mkv").apply { deleteOnExit() }
        val recording = testRecording(filePath = src.absolutePath)
        seedSettings()
        val received = collectEvents()

        // Both calls happen before the scheduler runs anything: the first call's queued job is already
        // active, so the second one must find the run occupied rather than start a rival over the
        // shared progress flows.
        vm.exportRecording(recording, RecordingExportKind.RAW_FOLDER)
        vm.exportRecording(recording, RecordingExportKind.RAW_FOLDER)
        advanceUntilIdle()

        val refusal = expectIs<RecordingsUiEvent.Message>(received.first())
        assertEquals(strings(R.string.recordings_run_busy), refusal.text)
        assertEquals(strings(R.string.recordings_toast_exported_to, gateway.folderDisplayName), expectIs<RecordingsUiEvent.Message>(received[1]).text)
        expectIs<RecordingsUiEvent.ExportCompleted>(received[2])
        assertEquals(3, received.size, "only the first run may deliver")
        assertEquals(1, gateway.safSaveCalls.size, "the refused request must not write through the gateway")
    }

    @Test
    fun `cancelActiveRun cancels the transcode, clears every progress flow and says so`() = runViewModelTest {
        val src = File.createTempFile("clip", ".mkv").apply { deleteOnExit() }
        val recording = testRecording(filePath = src.absolutePath)
        seedSettings()
        // A transcode that suspends until its waiting job is cancelled: it holds the run open
        // mid-flight, which is the only state a cancel has to be tested against. The fake records the
        // call before awaiting, so a non-empty transcodeCalls is the proof the run reached the encode.
        transcoder.blockTranscode = CompletableDeferred()

        vm.exportRecording(recording, RecordingExportKind.FOLDER)
        advanceUntilIdle()
        assertEquals(1, transcoder.transcodeCalls.size, "the run must have reached the transcode")
        expectIs<ExportRunProgress>(vm.exportProgress.value)

        vm.cancelActiveRun()
        advanceUntilIdle()

        assertNone(vm.exportProgress.value, "the strip must be dropped")
        assertNone(vm.batchProgress.value)
        assertNone(vm.serverDownload.value)
        assertTrue(strings.emittedResource(R.string.recordings_run_cancelled))
    }

    @Test
    fun `RAW_FOLDER export ships the original file through the gateway and celebrates`() = runViewModelTest {
        val src = File(storageDirs.recordingsDir, "motion.mkv").apply { writeText("raw-bytes") }
        val recording = testRecording(filePath = src.absolutePath)
        seedSettings()
        val received = collectEvents()

        vm.exportRecording(recording, RecordingExportKind.RAW_FOLDER)
        advanceUntilIdle()

        val deliveredMessage = expectIs<RecordingsUiEvent.Message>(received[0])
        assertEquals(strings(R.string.recordings_toast_exported_to, gateway.folderDisplayName), deliveredMessage.text)
        expectIs<RecordingsUiEvent.ExportCompleted>(received[1])
        assertEquals(2, received.size)

        val (folderUri, delivered, displayName) = gateway.safSaveCalls.single()
        assertEquals("content://fake/export", folderUri)
        assertEquals(src.absolutePath, delivered.absolutePath, "RAW_FOLDER must ship the original, not a re-encode")
        assertEquals("motion.mkv", displayName)
        assertTrue(transcoder.transcodeCalls.isEmpty(), "RAW_FOLDER never transcodes")
        assertNone(vm.exportProgress.value, "the finished run must leave no progress behind")
    }

    @Test
    fun `delete of a recording whose file survives removal reports the orphan and drops the row`() = runViewModelTest {
        // A non-empty directory is a path File.delete cannot reclaim on any desktop OS — the stand-in
        // for a file a lingering media handle keeps alive.
        val orphanDir = File(storageDirs.recordingsDir, "orphan.mkv").apply { mkdirs() }
        File(orphanDir, "inside").writeText("x")
        val recording = testRecording(filePath = orphanDir.absolutePath)
        repository.insertRecording(recording)

        vm.deleteRecording(recording)
        advanceUntilIdle()

        assertNone(recordingDao.rows.singleOrNull { it.id == recording.id }, "the row must be gone")
        assertTrue(strings.emittedResource(R.string.recordings_toast_deleted_orphan))
        assertTrue(orphanDir.exists(), "the fixture must really be undeletable")
        File(orphanDir, "inside").delete()
        orphanDir.delete()
    }

    @Test
    fun `bulk delete through the view model skips protected clips and leaves multi-select`() = runViewModelTest {
        repository.upsertDevice(testDevice(id = 1L))
        repository.insertRecording(testRecording(id = 1, deviceId = 1L, filePath = "clip1.mkv"))
        repository.insertRecording(
            testRecording(id = 2, deviceId = 1L, timestamp = 1_700_000_000_001L, filePath = "clip2.mkv")
                .copy(isProtected = true)
        )
        // Both derived flows only compute while subscribed, so keep one collector alive on each.
        backgroundScope.launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) { vm.selectionInfo.collect {} }
        val received = collectEvents()

        vm.toggleSelection(1L)
        vm.toggleSelection(2L)
        advanceUntilIdle()
        assertEquals(2, vm.selectionInfo.value.selectedCount)
        assertEquals(1, vm.selectionInfo.value.protectedCount)

        vm.deleteSelected()
        advanceUntilIdle()

        assertNone(recordingDao.rows.singleOrNull { it.id == 1L }, "the unprotected clip must be gone")
        assertEquals(2L, recordingDao.rows.singleOrNull { it.id == 2L }?.id, "a protected clip survives a bulk delete")
        assertTrue(vm.selectedIds.value.isEmpty(), "the batch clears once it has been acted on")
        assertTrue(
            received.any { it is RecordingsUiEvent.Message && it.text.startsWith("plural-") },
            "the skipped protected clip is reported, not dropped silently",
        )
    }

    @Test
    fun `device, event-type and search filters narrow items and flip filtersActive`() = runViewModelTest {
        val gate = testDevice(id = 1L, name = "Doorbell")
        val garage = testDevice(id = 2L, name = "Garage")
        repository.upsertDevice(gate)
        repository.upsertDevice(garage)
        repository.insertRecording(
            testRecording(id = 1, deviceId = 1L, deviceName = "Doorbell", eventType = EventType.MOTION, filePath = "clip1.mkv")
        )
        repository.insertRecording(
            testRecording(id = 2, deviceId = 2L, deviceName = "Garage", eventType = EventType.RING, timestamp = 1_700_000_000_001L, filePath = "clip2.mkv")
        )
        repository.insertRecording(
            testRecording(id = 3, deviceId = 1L, deviceName = "Doorbell", eventType = EventType.NOISE, timestamp = 1_700_000_000_002L, filePath = "clip3.mkv")
        )

        // The list is a WhileSubscribed StateFlow, so keep one collector alive on the background scope and
        // read the settled value after each change rather than betting on how many intermediate emissions
        // the nested filter combines happen to publish for a given mutation.
        backgroundScope.launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(3, vm.uiState.value.items.size)
        assertFalse(vm.uiState.value.filtersActive)

        vm.selectDeviceFilter(1L)
        advanceUntilIdle()
        assertEquals(listOf(3L, 1L), vm.uiState.value.localRecordings.map { it.id })
        assertTrue(vm.uiState.value.filtersActive)

        vm.selectDeviceFilter(null)
        vm.selectEventTypeFilter(EventType.RING)
        advanceUntilIdle()
        assertEquals(listOf(2L), vm.uiState.value.localRecordings.map { it.id })
        assertTrue(vm.uiState.value.filtersActive)

        vm.selectEventTypeFilter(null)
        vm.setSearchQuery("garage")
        advanceUntilIdle()
        assertEquals(listOf(2L), vm.uiState.value.localRecordings.map { it.id })
        assertTrue(vm.uiState.value.filtersActive)

        vm.clearFilters()
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.items.size)
        assertFalse(vm.uiState.value.filtersActive)
    }

    @Test
    fun `a clip that folded in a second trigger matches either event-type filter`() = runViewModelTest {
        repository.upsertDevice(testDevice(id = 1L))
        repository.insertRecording(
            testRecording(id = 1, deviceId = 1L, eventType = EventType.MOTION, filePath = "clip.mkv").let {
                it.copy(alsoEventTypes = listOf(EventType.RING))
            }
        )
        assertTrue(EventType.RING in recordingDao.rows.single().triggerTypes)

        vm.uiState.test {
            awaitItem()
            vm.selectEventTypeFilter(EventType.RING)
            assertEquals(listOf(1L), awaitItem().localRecordings.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
