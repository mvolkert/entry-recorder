package io.github.mvolkert.entryrecorder.ui.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RecordingsUiState(
    val recordings: List<RecordingEntity> = emptyList(),
    val devices: List<DeviceEntity> = emptyList(),
    val selectedDeviceId: Long? = null,
    val selectedEventType: EventType? = null,
    val searchQuery: String = "",
    val totalStorageBytes: Long = 0,
    val isLoading: Boolean = true
)

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EntryRecorderApp
    private val repository = app.repository

    private val _selectedDeviceId = MutableStateFlow<Long?>(null)
    private val _selectedEventType = MutableStateFlow<EventType?>(null)
    private val _searchQuery = MutableStateFlow("")

    val uiState: StateFlow<RecordingsUiState> = combine(
        repository.allRecordings,
        repository.allDevices,
        _selectedDeviceId,
        _selectedEventType,
        _searchQuery
    ) { recordings, devices, deviceId, eventType, query ->
        val filtered = recordings.filter { recording ->
            val matchesDevice = deviceId == null || recording.deviceId == deviceId
            val matchesType = eventType == null || recording.eventType == eventType
            val matchesQuery = query.isBlank() ||
                    recording.deviceName.contains(query, ignoreCase = true) ||
                    (recording.note?.contains(query, ignoreCase = true) == true)
            matchesDevice && matchesType && matchesQuery
        }

        val totalBytes = recordings.sumOf { it.fileSizeBytes }

        RecordingsUiState(
            recordings = filtered,
            devices = devices,
            selectedDeviceId = deviceId,
            selectedEventType = eventType,
            searchQuery = query,
            totalStorageBytes = totalBytes,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordingsUiState())

    fun selectDeviceFilter(deviceId: Long?) {
        _selectedDeviceId.value = deviceId
    }

    fun selectEventTypeFilter(eventType: EventType?) {
        _selectedEventType.value = eventType
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun deleteRecording(recording: RecordingEntity) {
        viewModelScope.launch {
            repository.deleteRecording(recording)
        }
    }

    fun toggleProtection(recording: RecordingEntity) {
        viewModelScope.launch {
            repository.setRecordingProtected(recording.id, !recording.isProtected)
        }
    }
}
