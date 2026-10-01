package io.github.mvolkert.entryrecorder.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.backup.AppBackup
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingClient
import io.github.mvolkert.entryrecorder.service.MonitorStatusHolder
import io.github.mvolkert.entryrecorder.worker.RetentionCleanupWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val devices: List<DeviceEntity> = emptyList(),
    val appSettings: AppSettingsEntity = AppSettingsEntity(),
    val totalStorageBytes: Long = 0,
    /** Per-device health of the camera event stream (doorbell / camera-motion / noise), for capability display. */
    val eventQualities: Map<Long, ConnectionQuality> = emptyMap(),
    /** Per-device health of the HTTP snapshot path (live view / recording / in-app motion analysis). */
    val snapshotQualities: Map<Long, ConnectionQuality> = emptyMap(),
    val isLoading: Boolean = true
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EntryRecorderApp
    private val repository = app.repository
    private val serverClient = ServerRecordingClient()

    // Includes the recordings flow so "Total storage" stays current after adds/deletes/cleanup
    // without leaving the screen (previously it only refreshed when devices/settings changed).
    val uiState: StateFlow<SettingsUiState> = combine(
        repository.allDevices,
        repository.settingsFlow,
        repository.allRecordings,
        MonitorStatusHolder.eventQualities,
        MonitorStatusHolder.snapshotQualities
    ) { devices, settings, recordings, eventQualities, snapshotQualities ->
        SettingsUiState(
            devices = devices,
            appSettings = settings ?: AppSettingsEntity(),
            totalStorageBytes = recordings.sumOf { it.fileSizeBytes },
            eventQualities = eventQualities,
            snapshotQualities = snapshotQualities,
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
                onResult(true, getApplication<Application>().getString(R.string.settings_server_connect_success))
            } else {
                onResult(
                    false,
                    result.exceptionOrNull()?.localizedMessage
                        ?: getApplication<Application>().getString(R.string.settings_connection_failed)
                )
            }
        }
    }

    fun triggerCleanupNow() {
        val work = OneTimeWorkRequestBuilder<RetentionCleanupWorker>().build()
        WorkManager.getInstance(getApplication()).enqueue(work)
    }

    // --- Backup & restore (settings + devices, via SAF, Gson-serialized) ---

    /** Writes a JSON backup of app settings + all devices to the user-chosen [uri]. */
    fun exportBackup(uri: Uri, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val backup = AppBackup(
                    exportedAt = System.currentTimeMillis(),
                    appSettings = repository.getSettings(),
                    devices = repository.getAllDevicesList()
                )
                val json = com.google.gson.Gson().toJson(backup)
                val resolver = getApplication<Application>().contentResolver
                resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException(
                        getApplication<Application>().getString(R.string.settings_backup_open_write_failed)
                    )
                onResult(
                    true,
                    getApplication<Application>()
                        .getString(R.string.settings_backup_export_success, backup.devices.size)
                )
            } catch (e: Exception) {
                onResult(
                    false,
                    getApplication<Application>().getString(
                        R.string.settings_backup_export_failed,
                        e.localizedMessage ?: getApplication<Application>().getString(R.string.settings_unknown_error)
                    )
                )
            }
        }
    }

    /**
     * Restores settings + devices from a backup JSON at [uri]. Devices are upserted by id (so their
     * existing recordings keep working); devices not present in the file are left untouched.
     */
    fun restoreBackup(uri: Uri, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val resolver = getApplication<Application>().contentResolver
                val json = resolver.openInputStream(uri)?.use {
                    String(it.readBytes(), Charsets.UTF_8)
                } ?: throw IllegalStateException(
                    getApplication<Application>().getString(R.string.settings_backup_open_read_failed)
                )
                val backup = com.google.gson.Gson().fromJson(json, AppBackup::class.java)
                    ?: throw IllegalStateException(
                        getApplication<Application>().getString(R.string.settings_backup_invalid_file)
                    )

                backup.appSettings?.let { repository.updateSettings(it.copy(id = 1)) }
                backup.devices.forEach { repository.upsertDevice(it) }

                onResult(
                    true,
                    getApplication<Application>()
                        .getString(R.string.settings_backup_restore_success, backup.devices.size)
                )
            } catch (e: Exception) {
                onResult(
                    false,
                    getApplication<Application>().getString(
                        R.string.settings_backup_restore_failed,
                        e.localizedMessage ?: getApplication<Application>().getString(R.string.settings_unknown_error)
                    )
                )
            }
        }
    }
}
