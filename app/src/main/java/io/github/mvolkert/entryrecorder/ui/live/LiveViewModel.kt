package io.github.mvolkert.entryrecorder.ui.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import io.github.mvolkert.entryrecorder.service.MonitorStatusHolder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LiveUiState(
    val devices: List<DeviceEntity> = emptyList(),
    /** Ids of devices with an active recording (local or server) — drives the REC badge. */
    val recordingDeviceIds: Set<Long> = emptySet(),
    /** Per-device monitoring status published by IntercomMonitorService — drives the status dot. */
    val monitorStatuses: Map<Long, MonitorStatus> = emptyMap(),
    val isLoading: Boolean = true
)

class LiveViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EntryRecorderApp
    private val repository = app.repository

    // All inputs are observable: the Room devices flow, the recorder's active-device StateFlow
    // (REC badge) and the monitor service's status map (monitoring dot), so the Live screen
    // re-renders immediately when devices change, a recording starts/stops or motion is detected.
    val uiState: StateFlow<LiveUiState> = combine(
        repository.allDevices,
        app.recorder.activeDeviceIds,
        MonitorStatusHolder.statuses
    ) { devices, recordingIds, monitorStatuses ->
        LiveUiState(
            devices = devices,
            recordingDeviceIds = recordingIds,
            monitorStatuses = monitorStatuses,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LiveUiState())

    fun startManualRecording(device: DeviceEntity) {
        app.recorder.startRecording(device, EventType.MANUAL, 120)
    }

    fun stopManualRecording(device: DeviceEntity) {
        app.recorder.stopRecording(device.id)
    }
}
