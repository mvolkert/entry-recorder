package io.github.mvolkert.entryrecorder.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.backup.AppBackup
import io.github.mvolkert.entryrecorder.data.device.IntercomDeviceFactory
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.local.entity.normalizedForRestore
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingClient
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.service.MonitorStatusRegistry
import io.github.mvolkert.entryrecorder.sip.SipCallManager
import io.github.mvolkert.entryrecorder.ui.theme.UiModePrefs
import io.github.mvolkert.entryrecorder.util.ExportFolderAccess
import io.github.mvolkert.entryrecorder.util.ExportGateway
import io.github.mvolkert.entryrecorder.util.SafExportedFile
import io.github.mvolkert.entryrecorder.util.StorageUsage
import io.github.mvolkert.entryrecorder.util.thumbnailUsageBytes
import io.github.mvolkert.entryrecorder.util.thumbnailsDirOf
import io.github.mvolkert.entryrecorder.worker.RetentionCleanupWorker
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** One-shot results the screen turns into snack bar messages. */
sealed interface SettingsUiEvent {
    data class Message(val text: String, val short: Boolean = false) : SettingsUiEvent
}

data class SettingsUiState(
    val devices: List<DeviceEntity> = emptyList(),
    val appSettings: AppSettingsEntity = AppSettingsEntity(),
    /** Per-device health of the camera event stream (doorbell / camera-motion / noise), for capability display. */
    val eventQualities: Map<Long, ConnectionQuality> = emptyMap(),
    /** Per-device health of the HTTP snapshot path (live view / recording / in-app motion analysis). */
    val snapshotQualities: Map<Long, ConnectionQuality> = emptyMap(),
    val isLoading: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: IntercomRepository,
    private val serverClient: ServerRecordingClient,
    private val monitorStatusRegistry: MonitorStatusRegistry,
    private val gson: Gson,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val exportGateway: ExportGateway,
    private val snapshotClient: HttpSnapshotClient,
    private val deviceFactory: IntercomDeviceFactory,
    private val sipCallManager: SipCallManager,
) : ViewModel() {

    private val tag = "SettingsViewModel"

    private val _events = Channel<SettingsUiEvent>(Channel.BUFFERED)

    /** Backup, server-test and folder-grant outcomes; the screen decides how to display them. */
    val events: Flow<SettingsUiEvent> = _events.receiveAsFlow()

    private val probeTick = MutableStateFlow(0L)

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.allDevices,
        repository.settingsFlow,
        monitorStatusRegistry.eventQualities,
        monitorStatusRegistry.snapshotQualities
    ) { devices, settings, eventQualities, snapshotQualities ->
        SettingsUiState(
            devices = devices,
            appSettings = settings ?: AppSettingsEntity(),
            eventQualities = eventQualities,
            snapshotQualities = snapshotQualities,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    /**
     * Whether the stored export folder is still writable. Probed off the main thread whenever the folder
     * changes or the screen asks for a re-check: a grant the user revoked, a folder they deleted and a
     * URI that only came back from a backup file are otherwise invisible until an export fails.
     */
    val exportFolderAccess: StateFlow<ExportFolderAccess> = combine(
        repository.settingsFlow.map { it?.exportFolderUri.orEmpty() },
        probeTick
    ) { folderUri, tick -> folderUri to tick }
        .distinctUntilChanged()
        .map { (folderUri, _) -> exportGateway.probeExportFolderAccess(context, folderUri) }
        .flowOn(ioDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExportFolderAccess.UNKNOWN)

    /**
     * What the archive occupies in the app's private storage, split by kind. Measured off the main thread
     * because the thumbnails half is a directory listing; the recordings half comes straight from the row
     * stream, so a delete or a cleanup pass updates the number without leaving the screen.
     */
    val storageUsage: StateFlow<StorageUsage> = combine(
        repository.allRecordings.map { recordings -> recordings.sumOf { it.fileSizeBytes } },
        probeTick
    ) { recordingsBytes, tick -> recordingsBytes to tick }
        .distinctUntilChanged()
        .map { key ->
            StorageUsage(
                recordingsBytes = key.first,
                thumbnailBytes = thumbnailUsageBytes(thumbnailsDirOf(context))
            )
        }
        .flowOn(ioDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StorageUsage())

    /**
     * Bytes the export folder holds, or null when there is nothing to measure — no folder chosen, a grant
     * the user revoked, a listing the provider refused, or a probe that has not answered yet. Listing a tree
     * is a provider round trip per document, so this only runs on the same ticks that re-probe the grant.
     *
     * The query is a third-party `ContentProvider`, so it fails as readily as it succeeds, and this flow
     * sits in a `stateIn(viewModelScope…)`: an exception escaping here is uncaught and kills the screen
     * while it is being opened. A folder that cannot be read is reported as no number, never as a crash.
     */
    val externalUsageBytes: StateFlow<Long?> = combine(
        repository.settingsFlow.map { it?.exportFolderUri.orEmpty() },
        probeTick,
        exportFolderAccess
    ) { folderUri, tick, access -> folderUri to (tick to access) }
        .distinctUntilChanged()
        .map { (folderUri, probe) ->
            val access = probe.second
            if (folderUri.isBlank() || access != ExportFolderAccess.OK) null
            else runCatching {
                exportGateway.listSafExportedFiles(context, folderUri.toUri())
                    .filter(SafExportedFile::isAppCreated)
                    .sumOf { it.sizeBytes }
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w(tag, "Export folder listing for $folderUri failed", it)
            }.getOrNull()
        }
        .flowOn(ioDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * The name the document provider gives the chosen export folder, "" while none is picked or the query
     * has not answered. It is a number-free label, so a provider that refuses the query is reported as no
     * name rather than as a broken folder — [externalUsageBytes] and [exportFolderAccess] already say
     * whether the folder works. Resolved here because the name costs a ContentProvider round trip, which
     * the storage screen used to pay on the UI thread inside a `remember`.
     */
    val exportFolderName: StateFlow<String> = repository.settingsFlow
        .map { it?.exportFolderUri.orEmpty() }
        .distinctUntilChanged()
        .map { folderUri ->
            if (folderUri.isBlank()) "" else runCatching {
                exportGateway.safFolderDisplayName(folderUri.toUri())
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w(tag, "Export folder name for $folderUri could not be read", it)
            }.getOrDefault("")
        }
        .flowOn(ioDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /**
     * Persists [device] locally, then — only in PYTHON_SERVER mode — best-effort syncs it to the server
     * (register the first time, update thereafter) so the server row carries the same capture config. The
     * local save is authoritative and never blocked by the server: a registration failure just leaves
     * `serverDeviceId` null, and the record-time path in RtspStreamRecorder registers just in time.
     */
    fun saveDevice(device: DeviceEntity) {
        viewModelScope.launch {
            val savedId = repository.saveDevice(device)
            val settings = repository.getSettings()
            if (settings.recordingMode != RecordingMode.PYTHON_SERVER) return@launch
            val current = repository.getDeviceById(savedId) ?: return@launch
            val serverUrl = settings.serverBaseUrl
            val apiKey = settings.serverApiKey.ifBlank { null }
            val serverDeviceId = current.serverDeviceId
            if (serverDeviceId == null) {
                serverClient.registerDevice(serverUrl, apiKey, current)
                    .onSuccess { id -> repository.saveDevice(current.copy(serverDeviceId = id)) }
                    .onFailure { Log.w(tag, "Server registration for ${current.name} deferred to record time", it) }
            } else {
                serverClient.updateServerDevice(serverUrl, apiKey, serverDeviceId, current)
                    .onFailure { Log.w(tag, "Server update for ${current.name} (id $serverDeviceId) failed", it) }
            }
        }
    }

    fun deleteDevice(device: DeviceEntity) {
        viewModelScope.launch {
            // Best-effort: drop the server row too when one was assigned, then delete locally regardless.
            // The server intentionally keeps the device's recordings (no cascade).
            val serverDeviceId = device.serverDeviceId
            if (serverDeviceId != null) {
                val settings = repository.getSettings()
                serverClient.deleteServerDevice(
                    settings.serverBaseUrl,
                    settings.serverApiKey.ifBlank { null },
                    serverDeviceId
                ).onFailure { Log.w(tag, "Server delete for ${device.name} (id $serverDeviceId) failed", it) }
            }
            repository.deleteDevice(device)
        }
    }

    fun updateSettings(settings: AppSettingsEntity) {
        // Mirror the mode into the synchronous SharedPreferences store first: MainActivity reads it
        // in attachBaseContext, which runs before Room is even opened on the next cold start.
        UiModePrefs.setThemeMode(context, settings.themeMode)
        viewModelScope.launch {
            repository.updateSettings(settings)
        }
    }

    /**
     * Stores the folder the tree picker returned as the export folder, keeping exactly one persistable
     * grant alive: the previous folder's permission is released first so the system's grant list does
     * not pile up across folders the user no longer uses.
     */
    fun onExportFolderSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val settings = repository.getSettings()
            val previous = settings.exportFolderUri
            if (previous.isNotBlank() && previous != uri.toString()) {
                releaseExportFolderGrant(previous)
            }
            try {
                context.contentResolver.takePersistableUriPermission(uri, EXPORT_FOLDER_FLAGS)
                repository.updateSettings(settings.copy(exportFolderUri = uri.toString()))
            } catch (e: SecurityException) {
                Log.w(tag, "Export folder grant refused for $uri", e)
                message(R.string.settings_toast_folder_permission_failed)
            }
        }
    }

    /** Forgets the export folder and drops its persistable grant with it. */
    fun clearExportFolder() {
        viewModelScope.launch {
            val settings = repository.getSettings()
            releaseExportFolderGrant(settings.exportFolderUri)
            repository.updateSettings(settings.copy(exportFolderUri = ""))
        }
    }

    /** Re-probes the current export folder, e.g. when the screen is shown again. */
    fun recheckExportFolder() {
        probeTick.update { it + 1 }
    }

    private fun releaseExportFolderGrant(folderUri: String) {
        if (folderUri.isBlank()) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(folderUri.toUri(), EXPORT_FOLDER_FLAGS)
        }.onFailure { Log.d(tag, "No live grant left to release for $folderUri", it) }
    }

    // --- Probes the create/edit form runs against unsaved values ---

    private val _connectionTest = MutableStateFlow<DeviceTestState>(DeviceTestState.Idle)

    /** Outcome of the HTTP probe against the values currently typed in. */
    val connectionTest: StateFlow<DeviceTestState> = _connectionTest.asStateFlow()

    private val _sipTest = MutableStateFlow<SipTestState>(SipTestState.Idle)

    /** Outcome of the registrar probe, which runs on a throw-away Linphone core. */
    val sipTest: StateFlow<SipTestState> = _sipTest.asStateFlow()

    private val _snapshotProbe = MutableStateFlow<SnapshotProbeState>(SnapshotProbeState.Idle)

    /** Outcome of the "Get FPS" burst. The screen writes the number into the field; this only measures. */
    val snapshotProbe: StateFlow<SnapshotProbeState> = _snapshotProbe.asStateFlow()

    private val measuredFpsDeviceId = MutableStateFlow<Long?>(null)

    /**
     * The snapshot rate the endpoint actually delivered lately, polled for whichever device the form is
     * showing. It lives here rather than in the section because [HttpSnapshotClient.achievedFps] is a
     * getter and not a stream: the loop that turned it into state used to sit in a `LaunchedEffect`, so
     * rotating the phone restarted it and left a gap where the reading should have been.
     */
    val measuredSnapshotFps: StateFlow<Float> = flow {
        while (true) {
            val deviceId = measuredFpsDeviceId.value
            emit(if (deviceId == null) 0f else snapshotClient.achievedFps(deviceId))
            delay(MEASURED_FPS_POLL_MS.milliseconds)
        }
    }.flowOn(ioDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    /**
     * Points [measuredSnapshotFps] at [deviceId], or stops the poll when null. Switching to a different
     * device — including leaving the form — also discards a finished probe, because a number that was
     * measured against another camera's address and paths would be shown as if it were this one's.
     */
    fun watchMeasuredSnapshotFps(deviceId: Long?) {
        if (measuredFpsDeviceId.value != deviceId) {
            _connectionTest.value = DeviceTestState.Idle
            _sipTest.value = SipTestState.Idle
            _snapshotProbe.value = SnapshotProbeState.Idle
        }
        measuredFpsDeviceId.value = deviceId
    }

    /**
     * Asks the intercom itself whether the typed address, port and credentials work, before anything is
     * saved. A second call while one is in flight is ignored rather than queued: two probes against the
     * same serial endpoint only slow both of them down.
     */
    fun testDeviceConnection(candidate: DeviceEntity) {
        if (_connectionTest.value is DeviceTestState.Running) return
        viewModelScope.launch {
            _connectionTest.value = DeviceTestState.Running
            val outcome = try {
                deviceFactory.createDevice(candidate).testConnection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A device class that cannot even report why it failed still has to leave the button idle.
                Log.w(tag, "Connection probe for ${candidate.name} threw", e)
                _connectionTest.value = DeviceTestState.Failed(e.localizedMessage)
                return@launch
            }
            _connectionTest.value = if (outcome.isSuccess) DeviceTestState.Passed
            else DeviceTestState.Failed(outcome.exceptionOrNull()?.localizedMessage)
        }
    }

    /** Asks a registrar whether the typed SIP account would register. See [SipCallManager.probeRegistration]. */
    fun testSipRegistration(candidate: DeviceEntity) {
        if (_sipTest.value is SipTestState.Running) return
        viewModelScope.launch {
            _sipTest.value = SipTestState.Running
            _sipTest.value = SipTestState.Completed(sipCallManager.probeRegistration(candidate))
        }
    }

    /**
     * Burst-measures what the snapshot endpoint can serve, for the typed path and credentials. Writes
     * nothing: the form owns its own fields, so the screen decides how to use the number.
     */
    fun probeSnapshotFps(candidate: DeviceEntity) {
        if (_snapshotProbe.value is SnapshotProbeState.Running) return
        viewModelScope.launch {
            _snapshotProbe.value = SnapshotProbeState.Running
            _snapshotProbe.value = SnapshotProbeState.Measured(snapshotClient.probeSnapshotFps(candidate))
        }
    }

    /** True while the monitor service holds a Linphone core, which is what a second probe would contend with. */
    val monitorUsesSip: Boolean get() = sipCallManager.hasActiveCore

    private val _isTestingServer = MutableStateFlow(false)
    val isTestingServer: StateFlow<Boolean> = _isTestingServer.asStateFlow()

    fun testServerConnection(url: String, apiKey: String) {
        viewModelScope.launch {
            _isTestingServer.value = true
            try {
                val result = serverClient.testConnection(url, apiKey.ifBlank { null })
                if (result.isSuccess) {
                    message(R.string.settings_server_connect_success)
                } else {
                    messageText(
                        result.exceptionOrNull()?.localizedMessage
                            ?: context.getString(R.string.settings_connection_failed)
                    )
                }
            } finally {
                _isTestingServer.value = false
            }
        }
    }

    fun triggerCleanupNow() {
        val work = OneTimeWorkRequestBuilder<RetentionCleanupWorker>().build()
        WorkManager.getInstance(context).enqueue(work)
        message(R.string.settings_toast_cleanup_triggered, short = true)
    }

    // --- Backup & restore (settings + devices, via SAF, Gson-serialized) ---

    /** Writes a JSON backup of app settings + all devices to the user-chosen [uri]. */
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val backup = AppBackup(
                    exportedAt = System.currentTimeMillis(),
                    appSettings = repository.getSettings(),
                    devices = repository.getAllDevicesList()
                )
                val json = gson.toJson(backup)
                withContext(ioDispatcher) {
                    context.contentResolver.openOutputStream(uri)
                        ?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: throw IllegalStateException(
                            context.getString(R.string.settings_backup_open_write_failed)
                        )
                }
                message(R.string.settings_backup_export_success, backup.devices.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "Backup export failed", e)
                message(
                    R.string.settings_backup_export_failed,
                    e.localizedMessage ?: context.getString(R.string.settings_unknown_error)
                )
            }
        }
    }

    /**
     * Restores settings + devices from a backup JSON at [uri]. Devices are upserted by id (so their
     * existing recordings keep working); devices not present in the file are left untouched.
     */
    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = withContext(ioDispatcher) {
                    context.contentResolver.openInputStream(uri)?.use {
                        String(it.readBytes(), Charsets.UTF_8)
                    } ?: throw IllegalStateException(
                        context.getString(R.string.settings_backup_open_read_failed)
                    )
                }
                val backup = gson.fromJson(json, AppBackup::class.java)
                    ?: throw IllegalStateException(
                        context.getString(R.string.settings_backup_invalid_file)
                    )

                backup.appSettings?.let {
                    UiModePrefs.setThemeMode(context, it.themeMode)
                    repository.updateSettings(it.normalizedForRestore().copy(id = 1))
                }
                // Reset serverDeviceId: a backup may have been taken against a different server, so the
                // restored devices re-register lazily against whichever server is configured now.
                backup.devices.forEach { repository.upsertDevice(it.copy(serverDeviceId = null)) }

                message(R.string.settings_backup_restore_success, backup.devices.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "Backup restore failed", e)
                message(
                    R.string.settings_backup_restore_failed,
                    e.localizedMessage ?: context.getString(R.string.settings_unknown_error)
                )
            }
        }
    }

    private fun message(@StringRes id: Int, vararg args: Any, short: Boolean = false) {
        _events.trySend(SettingsUiEvent.Message(context.getString(id, *args), short))
    }

    private fun messageText(text: String) {
        _events.trySend(SettingsUiEvent.Message(text))
    }

    companion object {
        private val EXPORT_FOLDER_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /** How often [measuredSnapshotFps] re-reads a rate that is a getter, not a stream. */
        private const val MEASURED_FPS_POLL_MS = 2_000L
    }
}
