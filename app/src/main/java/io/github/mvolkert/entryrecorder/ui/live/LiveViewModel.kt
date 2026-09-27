package io.github.mvolkert.entryrecorder.ui.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LiveUiState(
    val devices: List<DeviceEntity> = emptyList(),
    /** Ids of devices with an active recording (local or server) — drives the REC badge. */
    val recordingDeviceIds: Set<Long> = emptySet(),
    val isLoading: Boolean = true
)

class LiveViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EntryRecorderApp
    private val repository = app.repository

    // Both inputs are observable: the Room devices Flow plus the recorder's active-device
    // StateFlow, so the Live screen re-renders immediately when devices change or a recording
    // starts/stops (manually, via timeout auto-stop, or from ring/motion monitoring).
    val uiState: StateFlow<LiveUiState> = combine(
        repository.allDevices,
        app.recorder.activeDeviceIds
    ) { devices, recordingIds ->
        LiveUiState(
            devices = devices,
            recordingDeviceIds = recordingIds,
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
