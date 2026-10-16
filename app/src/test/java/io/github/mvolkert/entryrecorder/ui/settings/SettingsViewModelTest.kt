package io.github.mvolkert.entryrecorder.ui.settings

import com.google.gson.Gson
import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.domain.server.SyncDeviceUseCase
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.service.MonitorStatusRegistry
import io.github.mvolkert.entryrecorder.data.device.IntercomDeviceFactory
import io.github.mvolkert.entryrecorder.testing.FakeActiveServerRecordingDao
import io.github.mvolkert.entryrecorder.testing.FakeAppSettingsDao
import io.github.mvolkert.entryrecorder.testing.FakeBackupDocumentStore
import io.github.mvolkert.entryrecorder.testing.FakeDeviceDao
import io.github.mvolkert.entryrecorder.testing.FakeExportGateway
import io.github.mvolkert.entryrecorder.testing.FakeRecordingDao
import io.github.mvolkert.entryrecorder.testing.FakeRetentionCleanupScheduler
import io.github.mvolkert.entryrecorder.testing.FakeServerClient
import io.github.mvolkert.entryrecorder.testing.FakeSipRegistrar
import io.github.mvolkert.entryrecorder.testing.FakeThemeModeStore
import io.github.mvolkert.entryrecorder.testing.FakeAutoBootStore
import io.github.mvolkert.entryrecorder.testing.FakeUiStrings
import io.github.mvolkert.entryrecorder.testing.testDevice
import io.github.mvolkert.entryrecorder.testing.assertNone
import io.github.mvolkert.entryrecorder.testing.assertEquals
import io.github.mvolkert.entryrecorder.testing.assertTrue
import io.github.mvolkert.entryrecorder.util.AppStorageDirs
import io.github.mvolkert.entryrecorder.util.ExportFailureMonitor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The server-sync promises the device form makes: a PYTHON_SERVER save registers the device and keeps the
 * returned server id on the row, a registration failure defers it without ever blocking the local save,
 * the theme choice reaches the synchronous mirror before the Room write, and a delete drops the server
 * row when it can and still forgets the device locally when it cannot.
 */
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var deviceDao: FakeDeviceDao
    private lateinit var settingsDao: FakeAppSettingsDao
    private lateinit var repository: IntercomRepository
    private lateinit var server: FakeServerClient
    private lateinit var themeModes: FakeThemeModeStore
    private lateinit var autoBoots: FakeAutoBootStore
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        deviceDao = FakeDeviceDao()
        settingsDao = FakeAppSettingsDao()
        repository = IntercomRepository(deviceDao, FakeRecordingDao(), settingsDao, FakeActiveServerRecordingDao(), testDispatcher)
        server = FakeServerClient()
        themeModes = FakeThemeModeStore()
        autoBoots = FakeAutoBootStore()
        val root = File(System.getProperty("java.io.tmpdir"), "setvm-${System.nanoTime()}").apply { mkdirs() }
        vm = SettingsViewModel(
            repository = repository,
            serverClient = server,
            syncDevice = SyncDeviceUseCase(repository, server),
            monitorStatusRegistry = MonitorStatusRegistry(),
            gson = Gson(),
            strings = FakeUiStrings(),
            storageDirs = AppStorageDirs(File(root, "recordings"), File(root, "thumbnails"), File(root, "export")),
            themeModeStore = themeModes,
            autoBootStore = autoBoots,
            cleanupScheduler = FakeRetentionCleanupScheduler(),
            backupStore = FakeBackupDocumentStore(),
            ioDispatcher = testDispatcher,
            exportGateway = FakeExportGateway(),
            snapshotClient = HttpSnapshotClient(OkHttpClient(), testDispatcher),
            deviceFactory = IntercomDeviceFactory(OkHttpClient(), OkHttpClient(), testDispatcher, CoroutineScope(SupervisorJob())),
            sipRegistrar = FakeSipRegistrar(),
            exportFailureMonitor = ExportFailureMonitor(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Runs [body] inside runTest sharing [testDispatcher]'s scheduler, then flushes and leaves. */
    private fun runViewModelTest(body: suspend TestScope.() -> Unit) =
        runTest(StandardTestDispatcher(testDispatcher.scheduler)) {
            body()
            advanceUntilIdle()
        }

    /** Switches the stored settings to server mode with a key set, as the save paths expect. */
    private suspend fun enterServerMode() {
        repository.updateSettings(
            AppSettings(recordingMode = RecordingMode.PYTHON_SERVER, serverApiKey = "k")
        )
    }

    @Test
    fun `saving a new device in server mode registers it and persists the returned server id`() = runViewModelTest {
        enterServerMode()

        vm.saveDevice(testDevice(id = 0L, name = "Doorbell"))
        advanceUntilIdle()

        assertEquals(1, server.registerCalls.size)
        val row = deviceDao.rows.single()
        assertEquals(77L, row.serverDeviceId, "the id the server handed back must land on the row")
        assertTrue(server.updateCalls.isEmpty(), "a first save registers, it does not update")
    }

    @Test
    fun `a failed registration leaves serverDeviceId null but still saves the device locally`() = runViewModelTest {
        enterServerMode()
        server.registerResult = Result.failure(IllegalStateException("server offline"))

        vm.saveDevice(testDevice(id = 0L, name = "Doorbell"))
        advanceUntilIdle()

        assertEquals(1, server.registerCalls.size)
        val row = deviceDao.rows.single()
        assertNone(row.serverDeviceId, "a failed registration is deferred, not faked with an id")
        assertEquals("Doorbell", row.name, "the local save is authoritative")
    }

    @Test
    fun `saving an already registered device updates its server row instead of registering again`() = runViewModelTest {
        enterServerMode()
        repository.upsertDevice(testDevice(id = 1L, serverDeviceId = 55L))

        vm.saveDevice(testDevice(id = 1L, name = "Renamed", serverDeviceId = 55L))
        advanceUntilIdle()

        assertTrue(server.registerCalls.isEmpty(), "a device with an id is never re-registered")
        val (calledServerId, sentDevice) = server.updateCalls.single()
        assertEquals(55L, calledServerId)
        assertEquals("Renamed", sentDevice.name)
    }

    @Test
    fun `updateSettings mirrors the theme mode and writes the repository row`() = runViewModelTest {
        vm.updateSettings(AppSettings(themeMode = 2))
        advanceUntilIdle()

        assertEquals(2, themeModes.lastMode, "the synchronous mirror must see the mode")
        assertEquals(2, settingsDao.current?.themeMode, "and the Room row must carry it")
    }

    @Test
    fun `toggling auto boot persists the row and mirrors the synchronous boot gate`() = runViewModelTest {
        vm.updateSettings(AppSettings(autoBootEnabled = false))
        advanceUntilIdle()

        assertEquals(false, autoBoots.lastEnabled, "BootReceiver reads the synchronous mirror, not Room")
        assertEquals(false, settingsDao.current?.autoBootEnabled, "the Room row is the source of truth for the switch")
    }

    @Test
    fun `configuring the remote motion alert persists the flag and recipient`() = runViewModelTest {
        vm.updateSettings(AppSettings(motionAlertEnabled = true, motionAlertRecipient = "kitchen-phone"))
        advanceUntilIdle()

        assertEquals(true, settingsDao.current?.motionAlertEnabled, "the switch persists to the Room row")
        assertEquals("kitchen-phone", settingsDao.current?.motionAlertRecipient, "and so does the recipient")
    }

    @Test
    fun `deleting a registered device drops the server row and the local one`() = runViewModelTest {
        enterServerMode()
        val device = testDevice(id = 1L, serverDeviceId = 55L)
        repository.upsertDevice(device)

        vm.deleteDevice(device)
        advanceUntilIdle()

        assertEquals(listOf(55L), server.deleteDeviceCalls)
        assertTrue(deviceDao.rows.isEmpty(), "the local row goes regardless of the server outcome")
    }

    @Test
    fun `a server delete failure still removes the device locally`() = runViewModelTest {
        enterServerMode()
        server.deleteDeviceResult = Result.failure(IllegalStateException("server offline"))
        val device = testDevice(id = 1L, serverDeviceId = 55L)
        repository.upsertDevice(device)

        vm.deleteDevice(device)
        advanceUntilIdle()

        assertEquals(listOf(55L), server.deleteDeviceCalls)
        assertTrue(deviceDao.rows.isEmpty())
    }

    @Test
    fun `deleting an unregistered device never dials the server`() = runViewModelTest {
        enterServerMode()
        val device = testDevice(id = 1L)
        repository.upsertDevice(device)

        vm.deleteDevice(device)
        advanceUntilIdle()

        assertTrue(server.deleteDeviceCalls.isEmpty(), "no server id means no server call")
        assertTrue(deviceDao.rows.isEmpty(), "the local row still goes")
    }
}
