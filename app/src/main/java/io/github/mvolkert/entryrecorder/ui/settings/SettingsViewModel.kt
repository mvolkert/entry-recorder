package io.github.mvolkert.entryrecorder.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingClient
import io.github.mvolkert.entryrecorder.worker.RetentionCleanupWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val devices: List<DeviceEntity> = emptyList(),
    val appSettings: AppSettingsEntity = AppSettingsEntity(),
    val totalStorageBytes: Long = 0,
    val isLoading: Boolean = true
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EntryRecorderApp
    private val repository = app.repository
    private val serverClient = ServerRecordingClient()

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.allDevices,
        repository.settingsFlow
    ) { devices, settings ->
        val totalBytes = repository.getTotalStorageBytes()
        SettingsUiState(
            devices = devices,
            appSettings = settings ?: AppSettingsEntity(),
            totalStorageBytes = totalBytes,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun saveDevice(device: DeviceEntity) {
        viewModelScope.launch {
            repository.saveDevice(device)
        }
    }

    fun deleteDevice(device: DeviceEntity) {
        viewModelScope.launch {
            repository.deleteDevice(device)
        }
    }

    fun updateSettings(settings: AppSettingsEntity) {
        viewModelScope.launch {
            repository.updateSettings(settings)
        }
    }

    fun testServerConnection(url: String, apiKey: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = serverClient.testConnection(url, apiKey.ifBlank { null })
            if (result.isSuccess) {
                onResult(true, "Connected to Python server successfully!")
            } else {
                onResult(false, result.exceptionOrNull()?.localizedMessage ?: "Connection failed")
            }
        }
    }

    fun triggerCleanupNow() {
        val work = OneTimeWorkRequestBuilder<RetentionCleanupWorker>().build()
        WorkManager.getInstance(getApplication()).enqueue(work)
    }
}
