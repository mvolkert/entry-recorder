package io.github.mvolkert.entryrecorder.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.data.network.MjpegStreamReader
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.service.MonitorStatusRegistry
import io.github.mvolkert.entryrecorder.video.RecordingController
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LiveUiState(
    val devices: List<Device> = emptyList(),
    /** Ids of devices with an active recording (local or server) — drives the REC badge. */
    val recordingDeviceIds: Set<Long> = emptySet(),
    /** Per-device monitoring status published by IntercomMonitorService — drives the status dot. */
    val monitorStatuses: Map<Long, MonitorStatus> = emptyMap(),
    /** Health of each device's own event stream — dims the doorbell / camera-motion / noise glyphs. */
    val eventQualities: Map<Long, ConnectionQuality> = emptyMap(),
    /** App settings so the Live screen knows whether to pull live view through the server (Phase S). */
    val appSettings: AppSettings = AppSettings(),
    val isLoading: Boolean = true
)

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val repository: IntercomRepository,
    private val recorder: RecordingController,
    private val monitorStatusRegistry: MonitorStatusRegistry,
    // Exposed for the Live cards: the player must use the same shared snapshot/MJPEG clients as the
    // recorder, otherwise one camera gets polled through two connection pools with different timeouts.
    val mjpegReader: MjpegStreamReader,
    val snapshotClient: HttpSnapshotClient,
) : ViewModel() {

    // All inputs are observable: the Room devices flow, the recorder's active-device StateFlow
    // (REC badge) and the monitor service's status map (monitoring dot), so the Live screen
    // re-renders immediately when devices change, a recording starts/stops or motion is detected.
    val uiState: StateFlow<LiveUiState> = combine(
        repository.allDevices,
        recorder.activeDeviceIds,
        monitorStatusRegistry.statuses,
        monitorStatusRegistry.eventQualities,
        repository.settingsFlow
    ) { devices, recordingIds, monitorStatuses, eventQualities, settings ->
        LiveUiState(
            devices = devices,
            recordingDeviceIds = recordingIds,
            monitorStatuses = monitorStatuses,
            eventQualities = eventQualities,
            appSettings = settings ?: AppSettings(),
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LiveUiState())

    fun startManualRecording(device: Device) {
        recorder.startRecording(device, EventType.MANUAL, DEFAULT_MANUAL_RECORD_SECONDS)
    }

    fun stopManualRecording(device: Device) {
        recorder.stopRecording(device.id)
    }

    /**
     * Arms or disarms event monitoring for [Device] by persisting its isEnabled flag; the monitor
     * service reacts to the device flow and starts/stops it. Only touches monitoring, never an
     * in-flight recording — a triggered/manual capture is allowed to run out on its own.
     */
    fun toggleMonitoring(device: Device) {
        viewModelScope.launch {
            repository.saveDevice(device.copy(isEnabled = !device.isEnabled))
        }
    }

    companion object {
        /** Length of a manual recording started from the Live screen. */
        private const val DEFAULT_MANUAL_RECORD_SECONDS = 120
    }
}
