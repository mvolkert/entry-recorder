package io.github.mvolkert.entryrecorder.ui.recordings

import android.app.Application
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.local.entity.triggerTypes
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingClient
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingDto
import io.github.mvolkert.entryrecorder.util.ExportHelper
import io.github.mvolkert.entryrecorder.video.ExportTranscoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where a manually triggered export delivers the file. */
enum class RecordingExportKind {
    SHARE, GALLERY, FOLDER,

    /** Delivers the original captured file to the SAF export folder with no transcode, ignoring the Settings toggle. */
    RAW_FOLDER
}

data class RecordingsUiState(
    val items: List<GalleryItem> = emptyList(),
    val devices: List<DeviceEntity> = emptyList(),
    val selectedDeviceId: Long? = null,
    val selectedEventType: EventType? = null,
    val searchQuery: String = "",
    val totalStorageBytes: Long = 0,
    val isLoading: Boolean = true,
    val isServerLoading: Boolean = false
) {
    /** Only local recordings are selectable / exportable / deletable; server rows are read-only for now. */
    val localRecordings: List<RecordingEntity>
        get() = items.filterIsInstance<GalleryItem.Local>().map { it.entity }
}

/** One-shot export effects the screen has to perform (toasts, the share sheet). */
sealed interface RecordingsUiEvent {
    data class Message(val text: String, val short: Boolean = false) : RecordingsUiEvent

    /**
     * The share sheet has to be launched from an Activity context (the single-file path does not add
     * `FLAG_ACTIVITY_NEW_TASK`), so the ViewModel resolves the files and the screen performs the launch.
     * [recording] is non-null for a single local share; for a downloaded server row there is no entity, so
     * [deviceName] / [eventTypeLabel] carry the sheet's labels instead; both null means the multi-select batch.
     */
    data class Share(
        val files: List<File>,
        val recording: RecordingEntity?,
        val deviceName: String? = null,
        val eventTypeLabel: String? = null
    ) : RecordingsUiEvent

    /** Sent when a batch export finishes, so the screen can leave multi-select. */
    data object SelectionCleared : RecordingsUiEvent
}

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EntryRecorderApp
    private val repository = app.repository

    private val _selectedDeviceId = MutableStateFlow<Long?>(null)
    private val _selectedEventType = MutableStateFlow<EventType?>(null)
    private val _searchQuery = MutableStateFlow("")

    /** Ids currently checked in multi-select delete mode. */
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** Stage of the running single-item export, null while idle; [bodyRes] labels the dialog's percent line. */
    data class ExportRunProgress(val percent: Int, @StringRes val bodyRes: Int)

    /** Progress of the running single-item export (transcode or folder copy), null while idle. */
    private val _exportProgress = MutableStateFlow<ExportRunProgress?>(null)
    val exportProgress: StateFlow<ExportRunProgress?> = _exportProgress.asStateFlow()

    /** Done/total pair of a running multi-select export, null while idle. */
    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val batchProgress: StateFlow<Pair<Int, Int>?> = _batchProgress.asStateFlow()

    /** Percent (0..100) of a running server-video download, null while idle. */
    private val _serverDownload = MutableStateFlow<Int?>(null)
    val serverDownload: StateFlow<Int?> = _serverDownload.asStateFlow()

    private val _events = Channel<RecordingsUiEvent>(Channel.BUFFERED)

    /** Export results the screen turns into toasts or a share sheet. */
    val events: Flow<RecordingsUiEvent> = _events.receiveAsFlow()

    private val context: Context get() = getApplication()

    private val serverClient = ServerRecordingClient()

    /** Server rows are a lazy one-shot fetch, kept out of the Room flow so an offline server can't stall it. */
    private data class ServerListState(
        val items: List<ServerRecordingDto> = emptyList(),
        val isLoading: Boolean = false
    )
    private val _serverState = MutableStateFlow(ServerListState())

    private data class Filters(val deviceId: Long?, val eventType: EventType?, val query: String)
    private data class SourceData(
        val recordings: List<RecordingEntity>,
        val devices: List<DeviceEntity>,
        val settings: AppSettingsEntity?,
        val server: ServerListState
    )

    private val filterState =
        combine(_selectedDeviceId, _selectedEventType, _searchQuery) { deviceId, eventType, query ->
            Filters(deviceId, eventType, query)
        }

    private val sourceState = combine(
        repository.allRecordings,
        repository.allDevices,
        repository.settingsFlow,
        _serverState
    ) { recordings, devices, settings, server ->
        SourceData(recordings, devices, settings, server)
    }

    val uiState: StateFlow<RecordingsUiState> = combine(filterState, sourceState) { filters, source ->
        val selectedDevice = source.devices.firstOrNull { it.id == filters.deviceId }

        val localItems = source.recordings.filter { recording ->
            val matchesDevice = filters.deviceId == null || recording.deviceId == filters.deviceId
            // A clip that folded a second trigger in while it was still recording matches either tag, so a
            // doorbell pressed during a motion recording turns up under Ring as well as under Motion.
            val typeFilter = filters.eventType
            val matchesType = typeFilter == null || typeFilter in recording.triggerTypes
            val matchesQuery = filters.query.isBlank() ||
                    recording.deviceName.contains(filters.query, ignoreCase = true) ||
                    (recording.note?.contains(filters.query, ignoreCase = true) == true)
            matchesDevice && matchesType && matchesQuery
        }.map { GalleryItem.Local(it) }

        // Server rows only appear in PYTHON_SERVER mode; a row with no video path is skipped. Filtering
        // mirrors the local rules, except the device filter matches server rows by the server-assigned id
        // the Phase S sync stored, falling back to a name compare for a device not yet registered.
        val settings = source.settings
        val selectedServerId = selectedDevice?.serverDeviceId
        val remoteItems = if (settings?.recordingMode == RecordingMode.PYTHON_SERVER) {
            source.server.items.mapNotNull { dto ->
                val matchesDevice = filters.deviceId == null ||
                        if (selectedServerId != null) dto.deviceId == selectedServerId
                        else dto.deviceName == selectedDevice?.name
                val matchesType = filters.eventType == null ||
                        runCatching { EventType.valueOf(dto.eventType) }.getOrDefault(EventType.MANUAL) == filters.eventType
                val matchesQuery = filters.query.isBlank() ||
                        dto.deviceName.contains(filters.query, ignoreCase = true) ||
                        (dto.note?.contains(filters.query, ignoreCase = true) == true)
                val video = dto.videoUrl
                if (!matchesDevice || !matchesType || !matchesQuery || video == null) null
                else GalleryItem.Remote(
                    dto = dto,
                    thumbnailAbsoluteUrl = absoluteServerUrl(settings.serverBaseUrl, dto.thumbnailUrl, settings.serverApiKey),
                    videoAbsoluteUrl = absoluteServerUrl(settings.serverBaseUrl, video, settings.serverApiKey) ?: ""
                )
            }
        } else emptyList()

        RecordingsUiState(
            items = (localItems + remoteItems).sortedByDescending { it.timestamp },
            devices = source.devices,
            selectedDeviceId = filters.deviceId,
            selectedEventType = filters.eventType,
            searchQuery = filters.query,
            totalStorageBytes = source.recordings.sumOf { it.fileSizeBytes },
            isServerLoading = source.server.isLoading,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordingsUiState())

    /**
     * Prefixes a server-relative media path with the base URL and appends the API key as a query param,
     * because image and video loads go through Coil / ExoPlayer, which cannot set the X-API-Key header.
     */
    private fun absoluteServerUrl(baseUrl: String, relative: String?, apiKey: String): String? {
        if (relative == null) return null
        val full = baseUrl.trimEnd('/') + relative
        return if (apiKey.isBlank()) full else "$full?api_key=$apiKey"
    }

    /**
     * Fetches the server's completed recordings; called when the screen is shown. A no-op that also clears
     * any stale list outside PYTHON_SERVER mode, so the app never dials a server the user is not using.
     * A failure is reported as a toast and leaves whatever was already listed in place.
     */
    fun refreshServerRecordings() {
        viewModelScope.launch {
            val settings = repository.getSettings()
            if (settings.recordingMode != RecordingMode.PYTHON_SERVER) {
                _serverState.value = ServerListState()
                return@launch
            }
            _serverState.value = _serverState.value.copy(isLoading = true)
            serverClient.listRecordings(
                serverUrl = settings.serverBaseUrl,
                apiKey = settings.serverApiKey.ifBlank { null }
            ).onSuccess { list ->
                _serverState.value = ServerListState(items = list)
            }.onFailure { error ->
                _serverState.value = _serverState.value.copy(isLoading = false)
                toast(R.string.recordings_toast_server_unreachable, error.message ?: "")
            }
        }
    }

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

    // --- Multi-select bulk delete ---

    fun toggleSelection(id: Long) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    /** Selects every local recording currently visible after filters (server rows are read-only). */
    fun selectAllVisible() {
        _selectedIds.value = uiState.value.localRecordings.map { it.id }.toSet()
    }

    /** Deletes the selected recordings (and their files), then clears the selection. */
    fun deleteSelected() {
        val ids = _selectedIds.value
        val targets = uiState.value.localRecordings.filter { it.id in ids }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            repository.deleteRecordings(targets)
            _selectedIds.value = emptySet()
        }
    }

    fun toggleProtection(recording: RecordingEntity) {
        viewModelScope.launch {
            repository.setRecordingProtected(recording.id, !recording.isProtected)
        }
    }

    // --- Server recording actions (mutating, on the merged gallery) ---

    /** Flips the server-side protection flag and reflects the new state in the listed row on success. */
    fun toggleServerProtection(item: GalleryItem.Remote) {
        val dto = item.dto
        val newProtected = !dto.isProtected
        viewModelScope.launch {
            val settings = repository.getSettings()
            serverClient.setProtected(
                serverUrl = settings.serverBaseUrl,
                apiKey = settings.serverApiKey.ifBlank { null },
                recordingId = dto.id,
                isProtected = newProtected
            ).onSuccess {
                _serverState.update { state ->
                    state.copy(items = state.items.map { if (it.id == dto.id) it.copy(isProtected = newProtected) else it })
                }
            }.onFailure { error ->
                toast(R.string.recordings_toast_server_action_failed, error.message ?: "")
            }
        }
    }

    /** Deletes a completed recording on the server and drops it from the list on success. */
    fun deleteServerRecording(item: GalleryItem.Remote) {
        val dto = item.dto
        viewModelScope.launch {
            val settings = repository.getSettings()
            serverClient.deleteRecording(
                serverUrl = settings.serverBaseUrl,
                apiKey = settings.serverApiKey.ifBlank { null },
                recordingId = dto.id
            ).onSuccess {
                _serverState.update { state -> state.copy(items = state.items.filterNot { it.id == dto.id }) }
                toast(R.string.recordings_toast_server_deleted, short = true)
            }.onFailure { error ->
                toast(R.string.recordings_toast_server_action_failed, error.message ?: "")
            }
        }
    }

    /**
     * Downloads one server recording into the app cache, then delivers it like a local export. The server
     * file is already H.264, so there is no transcode step; SHARE/GALLERY/FOLDER just move the downloaded
     * copy; the download runs with a progress dialog because it crosses the network.
     */
    fun exportServerRecording(item: GalleryItem.Remote, kind: RecordingExportKind) {
        if (item.videoAbsoluteUrl.isBlank()) {
            toast(R.string.recordings_toast_download_failed, "no video url")
            return
        }
        viewModelScope.launch {
            val settings = repository.getSettings()
            if (kind == RecordingExportKind.FOLDER && settings.exportFolderUri.isBlank()) {
                toast(R.string.recordings_toast_set_folder_first)
                return@launch
            }
            _serverDownload.value = 0
            try {
                val result = serverClient.downloadVideo(
                    absoluteUrl = item.videoAbsoluteUrl,
                    targetDir = File(context.cacheDir, "export"),
                    baseName = "server_rec_${item.dto.id}"
                ) { percent -> _serverDownload.value = percent }
                _serverDownload.value = null
                result
                    .onSuccess { file -> deliverRemote(item, file, kind, settings.exportFolderUri) }
                    .onFailure { error -> toast(R.string.recordings_toast_download_failed, error.message ?: "") }
            } catch (e: CancellationException) {
                _serverDownload.value = null
                throw e
            }
        }
    }

    private suspend fun deliverRemote(item: GalleryItem.Remote, file: File, kind: RecordingExportKind, exportFolderUri: String) {
        val deviceName = item.deviceName
        val label = item.eventType.name
        when (kind) {
            RecordingExportKind.SHARE ->
                _events.trySend(RecordingsUiEvent.Share(listOf(file), recording = null, deviceName = deviceName, eventTypeLabel = label))

            RecordingExportKind.GALLERY -> withContext(Dispatchers.IO) {
                ExportHelper.saveFileToGallery(context, file, deviceName)
            }

            // RAW_FOLDER never reaches a server row (no local original), it just keeps the when exhaustive.
            RecordingExportKind.FOLDER, RecordingExportKind.RAW_FOLDER -> {
                val treeUri = exportFolderUri.toUri()
                val ok = withContext(Dispatchers.IO) {
                    ExportHelper.saveFileToSafFolder(context, treeUri, file, file.name)
                }
                if (ok) {
                    toast(R.string.recordings_toast_exported_to, ExportHelper.safFolderDisplayName(treeUri))
                } else {
                    toast(R.string.recordings_toast_export_folder_failed)
                }
            }
        }
    }

    // --- Manual export (lazy transcode) ---

    /**
     * Exports one recording to [kind]'s destination, transcoding the raw MJPEG MKV to H.264 first when
     * the Settings toggle allows it so the result plays in other apps. Transcoding runs only here, on an
     * explicit user action, never during capture. SHARE opens the system sheet and persists nothing;
     * FOLDER writes one file per recording (the re-encode when it exists, otherwise the original), so
     * the lossless original stays in-app and the folder isn't cluttered with near-duplicates.
     * RAW_FOLDER always ships the original. The blocking deliver work runs on IO with the progress
     * dialog driven by percent callbacks, so the UI stays responsive and shows what is happening.
     */
    fun exportRecording(recording: RecordingEntity, kind: RecordingExportKind) {
        viewModelScope.launch {
            // Read the settings when the action runs: which folder was picked and whether to re-encode
            // must not depend on whether anything happens to be subscribed to them.
            val settings = repository.getSettings()
            val src = File(recording.filePath)
            val goesToFolder = kind == RecordingExportKind.FOLDER || kind == RecordingExportKind.RAW_FOLDER
            val willTranscode = settings.transcodeOnExport && kind != RecordingExportKind.RAW_FOLDER &&
                    src.extension.equals("mkv", ignoreCase = true)

            if (goesToFolder) {
                if (settings.exportFolderUri.isBlank()) {
                    toast(R.string.recordings_toast_set_folder_first)
                    return@launch
                }
                if (!src.exists()) {
                    toast(R.string.recordings_toast_file_not_found, short = true)
                    return@launch
                }
            }
            // Raw file straight to Share/Gallery: no progress dialog, the deliver itself is on IO.
            if (!willTranscode && !goesToFolder) {
                deliver(recording, src, kind, settings.exportFolderUri)
                return@launch
            }

            try {
                if (willTranscode) {
                    _exportProgress.value = ExportRunProgress(0, R.string.recordings_export_progress_body)
                }
                val out = if (willTranscode) transcodeWithProgress(recording) else src
                deliver(recording, out, kind, settings.exportFolderUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(R.string.recordings_toast_export_failed, e.message ?: "")
            } finally {
                _exportProgress.value = null
            }
        }
    }

    /**
     * Exports every visible recording checked in multi-select, with the same lazy-transcode policy as
     * [exportRecording] and a k-of-n progress. A per-file transcode failure falls back to that file's
     * original instead of aborting the batch; Share collects all files into one system sheet, Gallery
     * and Folder report a final saved count.
     */
    fun exportSelected(kind: RecordingExportKind) {
        val targets = uiState.value.localRecordings.filter { it.id in _selectedIds.value }
        if (targets.isEmpty()) {
            toast(R.string.recordings_toast_nothing_selected)
            return
        }
        viewModelScope.launch {
            val settings = repository.getSettings()
            if ((kind == RecordingExportKind.FOLDER || kind == RecordingExportKind.RAW_FOLDER) &&
                settings.exportFolderUri.isBlank()
            ) {
                toast(R.string.recordings_toast_set_folder_first)
                return@launch
            }
            _batchProgress.value = 0 to targets.size
            val toShare = ArrayList<File>()
            var saved = 0
            var done = 0
            try {
                for (rec in targets) {
                    val src = File(rec.filePath)
                    val willTranscode = settings.transcodeOnExport &&
                            kind != RecordingExportKind.RAW_FOLDER &&
                            src.extension.equals("mkv", ignoreCase = true) && src.exists()
                    val out = if (willTranscode) {
                        try {
                            ExportTranscoder.transcodeToH264(context, rec)
                        } catch (_: Exception) {
                            src
                        }
                    } else src

                    when (kind) {
                        RecordingExportKind.SHARE -> toShare.add(out)
                        RecordingExportKind.GALLERY ->
                            withContext(Dispatchers.IO) {
                                if (ExportHelper.saveFileToGallery(context, out, rec, showToast = false)) saved++
                            }

                        RecordingExportKind.FOLDER, RecordingExportKind.RAW_FOLDER -> {
                            // One file per recording (H.264 when produced, else original) — as single-item.
                            val treeUri = settings.exportFolderUri.toUri()
                            withContext(Dispatchers.IO) {
                                if (ExportHelper.saveFileToSafFolder(context, treeUri, out, out.name)) saved++
                            }
                        }
                    }
                    _batchProgress.value = ++done to targets.size
                }

                when (kind) {
                    RecordingExportKind.SHARE ->
                        if (toShare.isNotEmpty()) _events.trySend(RecordingsUiEvent.Share(toShare, null))

                    RecordingExportKind.GALLERY -> toastPlural(
                        R.plurals.recordings_toast_saved_gallery, targets.size, saved, targets.size
                    )

                    RecordingExportKind.FOLDER, RecordingExportKind.RAW_FOLDER -> toastPlural(
                        R.plurals.recordings_toast_exported_folder, targets.size, saved, targets.size
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(R.string.recordings_toast_export_failed, e.message ?: "")
            } finally {
                _batchProgress.value = null
                clearSelection()
                _events.trySend(RecordingsUiEvent.SelectionCleared)
            }
        }
    }

    private suspend fun transcodeWithProgress(recording: RecordingEntity): File =
        ExportTranscoder.transcodeToH264(context, recording) { done, total ->
            _exportProgress.value = ExportRunProgress(
                if (total > 0) done * 100 / total else 0,
                R.string.recordings_export_progress_body
            )
        }

    private suspend fun deliver(recording: RecordingEntity, file: File, kind: RecordingExportKind, exportFolderUri: String) {
        when (kind) {
            RecordingExportKind.SHARE -> _events.trySend(RecordingsUiEvent.Share(listOf(file), recording))

            RecordingExportKind.GALLERY -> withContext(Dispatchers.IO) {
                ExportHelper.saveFileToGallery(context, file, recording)
            }

            RecordingExportKind.FOLDER, RecordingExportKind.RAW_FOLDER -> {
                val treeUri = exportFolderUri.toUri()
                val ok = withContext(Dispatchers.IO) {
                    ExportHelper.saveFileToSafFolder(context, treeUri, file, file.name) { percent ->
                        _exportProgress.value = ExportRunProgress(percent, R.string.recordings_copy_progress_body)
                    }
                }
                if (ok) {
                    toast(R.string.recordings_toast_exported_to, ExportHelper.safFolderDisplayName(treeUri))
                } else {
                    toast(R.string.recordings_toast_export_folder_failed)
                }
            }
        }
    }

    private fun toast(@StringRes id: Int, vararg args: Any, short: Boolean = false) {
        _events.trySend(RecordingsUiEvent.Message(context.getString(id, *args), short))
    }

    private fun toastPlural(@PluralsRes id: Int, quantity: Int, vararg args: Any) {
        val text = context.resources.getQuantityString(id, quantity, *args)
        _events.trySend(RecordingsUiEvent.Message(text))
    }
}
