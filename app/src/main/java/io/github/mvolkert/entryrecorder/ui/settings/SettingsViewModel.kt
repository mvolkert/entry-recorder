package io.github.mvolkert.entryrecorder.ui.settings

import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.backup.BackupRestoreOutcome
import io.github.mvolkert.entryrecorder.data.backup.BackupRestoreRunner
import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.server.ServerDeleteResult
import io.github.mvolkert.entryrecorder.domain.server.ServerSyncResult
import io.github.mvolkert.entryrecorder.domain.server.SyncDeviceUseCase
import io.github.mvolkert.entryrecorder.data.local.prefs.AutoBootStore
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.data.server.ServerClient
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.service.MonitorStatusRegistry
import io.github.mvolkert.entryrecorder.sip.SipRegistrar
import io.github.mvolkert.entryrecorder.ui.theme.ThemeModeStore
import io.github.mvolkert.entryrecorder.util.ExportFolderAccess
import io.github.mvolkert.entryrecorder.util.ExportFailureMonitor
import io.github.mvolkert.entryrecorder.util.StorageUsage
import io.github.mvolkert.entryrecorder.util.UiStrings
import io.github.mvolkert.entryrecorder.worker.RetentionCleanupScheduler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-shot results the screen turns into snack bar messages. */
sealed interface SettingsUiEvent {
    data class Message(val text: String, val short: Boolean = false) : SettingsUiEvent
}

data class SettingsUiState(
    val devices: List<Device> = emptyList(),
    val appSettings: AppSettings = AppSettings(),
    /** Per-device health of the camera event stream (doorbell / camera-motion / noise), for capability display. */
    val eventQualities: Map<Long, ConnectionQuality> = emptyMap(),
    /** Per-device health of the HTTP snapshot path (live view / recording / in-app motion analysis). */
    val snapshotQualities: Map<Long, ConnectionQuality> = emptyMap(),
    val isLoading: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: IntercomRepository,
    private val serverClient: ServerClient,
    private val syncDevice: SyncDeviceUseCase,
    private val monitorStatusRegistry: MonitorStatusRegistry,
    private val strings: UiStrings,
    // Everything the screen needs from the framework reaches this class as a JVM-only contract or a plain
    // value, so no Context is held here and the whole ViewModel can be constructed and driven from an
    // ordinary unit test. The only Android types left are the caller-supplied `Uri`s the SAF picker returns,
    // which pass straight through to the runners below.
    private val themeModeStore: ThemeModeStore,
    private val autoBootStore: AutoBootStore,
    private val cleanupScheduler: RetentionCleanupScheduler,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val exportFailureMonitor: ExportFailureMonitor,
    private val backupRestoreRunner: BackupRestoreRunner,
    private val storageHubRunner: StorageHubRunner,
    private val probeRunner: DeviceProbeRunner,
) : ViewModel() {

    private val tag = "SettingsViewModel"

    private val _events = Channel<SettingsUiEvent>(Channel.BUFFERED)

    /** Backup, server-test and folder-grant outcomes; the screen decides how to display them. */
    val events: Flow<SettingsUiEvent> = _events.receiveAsFlow()

    private val probeTick = MutableStateFlow(0L)

    /**
     * The process-wide retained export-write failure, surfaced on the storage hub row so a stalled
     * auto-export is visible without opening the folder screens. Cleared by the write paths on the next
     * good save.
     */
    val exportFailure: StateFlow<ExportFailureMonitor.Failure?> = exportFailureMonitor.failure

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.allDevices,
        repository.settingsFlow,
        monitorStatusRegistry.eventQualities,
        monitorStatusRegistry.snapshotQualities
    ) { devices, settings, eventQualities, snapshotQualities ->
        SettingsUiState(
            devices = devices,
            appSettings = settings ?: AppSettings(),
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
        .map { (folderUri, _) -> storageHubRunner.probeFolderAccess(folderUri) }
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
                thumbnailBytes = storageHubRunner.thumbnailCacheBytes()
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
            else storageHubRunner.exportedBytes(folderUri)
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
            if (folderUri.isBlank()) "" else storageHubRunner.folderDisplayName(folderUri)
        }
        .flowOn(ioDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /**
     * Persists [device] locally (authoritative), then best-effort syncs it to the server through
     * [SyncDeviceUseCase]; the local save is never blocked by the server. Only the messaging/logging of the
     * sync outcome is the ViewModel's job here.
     */
    fun saveDevice(device: Device) {
        viewModelScope.launch {
            when (val sync = syncDevice.saveAndSync(device).sync) {
                ServerSyncResult.Skipped -> Unit
                is ServerSyncResult.Registered ->
                    Log.i(tag, "Registered ${device.name} on the server as id ${sync.serverDeviceId}")
                is ServerSyncResult.Updated ->
                    Log.d(tag, "Updated ${device.name} on the server (id ${sync.serverDeviceId})")
                is ServerSyncResult.RegistrationDeferred ->
                    Log.w(tag, "Server registration for ${device.name} deferred to record time", sync.cause)
                is ServerSyncResult.UpdateFailed ->
                    Log.w(tag, "Server update for ${device.name} (id ${sync.serverDeviceId}) failed", sync.cause)
            }
        }
    }

    /** Drops the server row when one was assigned (best-effort), then deletes locally regardless. */
    fun deleteDevice(device: Device) {
        viewModelScope.launch {
            when (val sync = syncDevice.deleteAndSync(device)) {
                ServerDeleteResult.NotRegistered -> Unit
                ServerDeleteResult.Deleted -> Log.i(tag, "Deleted ${device.name} from the server")
                is ServerDeleteResult.Failed ->
                    Log.w(tag, "Server delete for ${device.name} (id ${sync.serverDeviceId}) failed", sync.cause)
            }
        }
    }

    fun updateSettings(settings: AppSettings) {
        // Mirror the mode into the synchronous SharedPreferences store first: MainActivity reads it
        // in attachBaseContext, which runs before Room is even opened on the next cold start.
        themeModeStore.setThemeMode(settings.themeMode)
        // Likewise the boot gate: BootReceiver reads the synchronous mirror on the boot broadcast, before
        // Room is warm, so the flag has to live there too for autostart to honour the user's choice.
        autoBootStore.setAutoBootEnabled(settings.autoBootEnabled)
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
            if (storageHubRunner.takeGrant(uri)) {
                repository.updateSettings(settings.copy(exportFolderUri = uri.toString()))
            } else {
                // The gateway already logged the refusal; the user just hears that it failed.
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

    private suspend fun releaseExportFolderGrant(folderUri: String) {
        storageHubRunner.releaseGrant(folderUri)
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
     * showing. The loop lives in [DeviceProbeRunner] because the rate it reads is a getter and not a
     * stream; the shared flow stays here so rotating the phone does not restart the poll and leave a gap
     * where the reading should have been.
     */
    val measuredSnapshotFps: StateFlow<Float> = probeRunner.measuredFps(measuredFpsDeviceId)
        .flowOn(ioDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

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
    fun testDeviceConnection(candidate: Device) {
        if (_connectionTest.alreadyRunning(DeviceTestState.Running)) return
        viewModelScope.launch {
            _connectionTest.value = DeviceTestState.Running
            _connectionTest.value = probeRunner.connectionTest(candidate)
        }
    }

    /** Asks a registrar whether the typed SIP account would register. See [SipRegistrar.probeRegistration]. */
    fun testSipRegistration(candidate: Device) {
        if (_sipTest.alreadyRunning(SipTestState.Running)) return
        viewModelScope.launch {
            _sipTest.value = SipTestState.Running
            _sipTest.value = probeRunner.sipRegistration(candidate)
        }
    }

    /**
     * Burst-measures what the snapshot endpoint can serve, for the typed path and credentials. Writes
     * nothing: the form owns its own fields, so the screen decides how to use the number.
     */
    fun probeSnapshotFps(candidate: Device) {
        if (_snapshotProbe.alreadyRunning(SnapshotProbeState.Running)) return
        viewModelScope.launch {
            _snapshotProbe.value = SnapshotProbeState.Running
            _snapshotProbe.value = probeRunner.snapshotFps(candidate)
        }
    }

    /** A second probe of the same kind is refused while one is in flight, rather than queued behind it. */
    private fun <T : Any> MutableStateFlow<T>.alreadyRunning(running: T): Boolean = value == running

    /** True while the monitor service holds a Linphone core, which is what a second probe would contend with. */
    val monitorUsesSip: Boolean get() = probeRunner.usesActiveRegistrar

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
                            ?: strings(R.string.settings_connection_failed)
                    )
                }
            } finally {
                _isTestingServer.value = false
            }
        }
    }

    fun triggerCleanupNow() {
        cleanupScheduler.runNow()
        message(R.string.settings_toast_cleanup_triggered, short = true)
    }

    // --- Backup & restore (settings + devices, via SAF, Gson-serialized) ---

    /** Writes a JSON backup of app settings + all devices to the user-chosen [uri]. */
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            when (val outcome = backupRestoreRunner.export(uri.toString())) {
                is BackupRestoreOutcome.Exported -> message(R.string.settings_backup_export_success, outcome.deviceCount)
                is BackupRestoreOutcome.Failed -> message(
                    R.string.settings_backup_export_failed,
                    outcome.cause.localizedMessage ?: strings(R.string.settings_unknown_error)
                )
                is BackupRestoreOutcome.Restored, BackupRestoreOutcome.Invalid -> Unit
            }
        }
    }

    /**
     * Restores settings + devices from a backup JSON at [uri]. Devices are upserted by id (so their
     * existing recordings keep working); devices not present in the file are left untouched.
     */
    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            when (val outcome = backupRestoreRunner.restore(uri.toString())) {
                is BackupRestoreOutcome.Restored -> message(R.string.settings_backup_restore_success, outcome.deviceCount)
                // A document that parsed to nothing is still reported through the restore-failed string, with
                // the invalid-file text as its argument, exactly as when the parse threw.
                BackupRestoreOutcome.Invalid -> message(
                    R.string.settings_backup_restore_failed,
                    strings(R.string.settings_backup_invalid_file)
                )

                is BackupRestoreOutcome.Failed -> message(
                    R.string.settings_backup_restore_failed,
                    outcome.cause.localizedMessage ?: strings(R.string.settings_unknown_error)
                )
                is BackupRestoreOutcome.Exported -> Unit
            }
        }
    }

    private fun message(@StringRes id: Int, vararg args: Any, short: Boolean = false) {
        _events.trySend(SettingsUiEvent.Message(strings(id, *args), short))
    }

    private fun messageText(text: String) {
        _events.trySend(SettingsUiEvent.Message(text))
    }
}
