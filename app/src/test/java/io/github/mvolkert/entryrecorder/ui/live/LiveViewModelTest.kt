package io.github.mvolkert.entryrecorder.ui.live

import app.cash.turbine.test
import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.data.network.MjpegStreamReader
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.service.MonitorStatusRegistry
import io.github.mvolkert.entryrecorder.testing.FakeActiveServerRecordingDao
import io.github.mvolkert.entryrecorder.testing.FakeAppSettingsDao
import io.github.mvolkert.entryrecorder.testing.FakeDeviceDao
import io.github.mvolkert.entryrecorder.testing.FakeRecordingController
import io.github.mvolkert.entryrecorder.testing.FakeRecordingDao
import io.github.mvolkert.entryrecorder.testing.testDevice
import io.github.mvolkert.entryrecorder.testing.assertEquals
import io.github.mvolkert.entryrecorder.testing.assertFalse
import io.github.mvolkert.entryrecorder.testing.assertTrue
import kotlinx.coroutines.Dispatchers
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
 * The Live screen's one source of truth: it must fuse the stored devices, the recorder's active ids and
 * the monitor service's status map into a single state, and arming or disarming a device must be a pure
 * persistence action that never disturbs an in-flight recording.
 */
class LiveViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var deviceDao: FakeDeviceDao
    private lateinit var repository: IntercomRepository
    private lateinit var recorder: FakeRecordingController
    private lateinit var registry: MonitorStatusRegistry
    private lateinit var vm: LiveViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        deviceDao = FakeDeviceDao()
        repository = IntercomRepository(
            deviceDao, FakeRecordingDao(), FakeAppSettingsDao(), FakeActiveServerRecordingDao(), testDispatcher
        )
        recorder = FakeRecordingController()
        registry = MonitorStatusRegistry()
        vm = LiveViewModel(
            repository = repository,
            recorder = recorder,
            monitorStatusRegistry = registry,
            mjpegReader = MjpegStreamReader(OkHttpClient(), testDispatcher),
            snapshotClient = HttpSnapshotClient(OkHttpClient(), testDispatcher),
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

    @Test
    fun `uiState fuses devices, active recordings, monitor statuses and event qualities`() = runViewModelTest {
        repository.upsertDevice(testDevice(id = 1L, name = "Doorbell"))
        repository.upsertDevice(testDevice(id = 2L, name = "Garage"))
        recorder.activeIds = setOf(2L)
        registry.update(1L, MonitorStatus.MONITORING)
        registry.updateEventQuality(1L, ConnectionQuality.DEGRADED)

        vm.uiState.test {
            val initial = awaitItem()
            assertTrue(initial.isLoading, "the shared flow starts from the loading default")

            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals(listOf(1L, 2L), state.devices.map { it.id })
            assertEquals(setOf(2L), state.recordingDeviceIds, "the REC badge follows the recorder's active ids")
            assertEquals(mapOf(1L to MonitorStatus.MONITORING), state.monitorStatuses)
            assertEquals(mapOf(1L to ConnectionQuality.DEGRADED), state.eventQualities)

            // Every input is observable: a recording that starts must reach the same state.
            recorder.activeIds = setOf(1L, 2L)
            assertEquals(setOf(1L, 2L), awaitItem().recordingDeviceIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleMonitoring persists the flipped flag and never touches an in-flight recording`() = runViewModelTest {
        repository.upsertDevice(testDevice(id = 1L, isEnabled = false))
        // A recording that is running on this device while its monitoring is toggled must survive untouched.
        recorder.activeIds = setOf(1L)

        vm.toggleMonitoring(testDevice(id = 1L, isEnabled = false))
        advanceUntilIdle()

        assertTrue(deviceDao.rows.single().isEnabled, "disarmed becomes armed through the repository")

        vm.toggleMonitoring(testDevice(id = 1L, isEnabled = true))
        advanceUntilIdle()

        assertFalse(deviceDao.rows.single().isEnabled)
        assertTrue(recorder.startCalls.isEmpty(), "monitoring changes are not recording changes")
        assertTrue(recorder.stopCalls.isEmpty(), "a triggered capture is allowed to run out on its own")
        assertEquals(setOf(1L), recorder.activeIds)
    }

    @Test
    fun `uiState falls back to the fresh settings row while none is stored`() = runViewModelTest {
        repository.upsertDevice(testDevice(id = 1L))

        vm.uiState.test {
            awaitItem()
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals(AppSettings(), state.appSettings, "a null settings row reads as the default")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
