package io.github.mvolkert.entryrecorder.ui.recordings

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.model.Recording
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.data.repository.RecordingDeleteResult
import io.github.mvolkert.entryrecorder.data.server.ServerClient
import io.github.mvolkert.entryrecorder.data.server.ServerMediaUrls
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingDto
import io.github.mvolkert.entryrecorder.util.ExportContext
import io.github.mvolkert.entryrecorder.util.ExportMetadata
import io.github.mvolkert.entryrecorder.util.ExportRunner
import io.github.mvolkert.entryrecorder.util.RecordingExportKind
import io.github.mvolkert.entryrecorder.util.ServerRecordingSource
import io.github.mvolkert.entryrecorder.util.UiStrings
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class RecordingsUiState(
    val items: List<GalleryItem> = emptyList(),
    val devices: List<Device> = emptyList(),
    val selectedDeviceId: Long? = null,
    val selectedEventType: EventType? = null,
    val searchQuery: String = "",
    val totalStorageBytes: Long = 0,
    val isLoading: Boolean = true,
    val isServerLoading: Boolean = false,
    /** True when at least one of the three filters is narrowing the list, which changes the empty state. */
    val filtersActive: Boolean = false,
    /** Server mode: the only state in which the list can be refreshed from the network by gesture. */
    val serverModeEnabled: Boolean = false
) {
    /** Only local recordings are selectable / exportable / deletable; server rows are read-only for now. */
    val localRecordings: List<Recording>
        get() = items.filterIsInstance<GalleryItem.Local>().map { it.recording }
}

/** One-shot export effects the screen has to perform (snack-bar messages, the share sheet). */
sealed interface RecordingsUiEvent {
    data class Message(val text: String, val short: Boolean = false) : RecordingsUiEvent

    /**
     * The share sheet has to be launched from an Activity context (the single-file path does not add
     * `FLAG_ACTIVITY_NEW_TASK`), so the ViewModel resolves the files and the screen performs the launch.
     * [metadata] carries the sheet's labels for a single-clip share; null means the multi-select batch.
     */
    data class Share(
        val files: List<File>,
        val metadata: ExportMetadata? = null,
    ) : RecordingsUiEvent

    /** Sent when a batch export finishes, so the screen can leave multi-select. */
    data object SelectionCleared : RecordingsUiEvent

    /**
     * An export wrote its files successfully, so the screen spends its celebration moment on it. Only
     * ever emitted on the success branches — a failure still reports as a plain [Message].
     */
    data object ExportCompleted : RecordingsUiEvent
}

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val repository: IntercomRepository,
    private val serverClient: ServerClient,
    private val strings: UiStrings,
    // The whole transcode → deliver → progress → celebrate pipeline lives in [ExportRunner]; the ViewModel
    // keeps only the state the strip and the snack bar read (the progress flows, the event channel) and the
    // intents that start, guard and cancel a run.
    private val exportRunner: ExportRunner,
) : ViewModel() {

    private val _selectedDeviceId = MutableStateFlow<Long?>(null)
    private val _selectedEventType = MutableStateFlow<EventType?>(null)
    private val _searchQuery = MutableStateFlow("")

    /** Ids currently checked in multi-select delete mode. */
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** Row the range gesture (long-press) measures from; null until something has been picked. */
    private var selectionAnchorId: Long? = null

    /** Stage of the running single-item export, null while idle; [bodyRes] labels the strip's percent line. */
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

    /**
     * The one export or download run that owns the progress strip. Three flows feed it because the runs are
     * mutually exclusive: that was automatic while their dialogs covered the screen, and with a strip the
     * list stays usable, so [runBusy] answers a second request instead of starting a rival run over the
     * same flows — two at once would show only one of them and clear the strip when the older one ends.
     */
    private var activeRun: Job? = null

    /**
     * Stops the running export or download. [H264Transcoder] checks cancellation per frame, so this
     * lands within one frame; the strip and the unfinished file are dropped by the cancelled run's own
     * `finally`, which is where it learns which output to delete.
     */
    fun cancelActiveRun() {
        activeRun?.cancel()
        message(R.string.recordings_run_cancelled)
    }

    /** True (and said out loud) when a request arrives while a run still owns the progress strip. */
    private fun runBusy(): Boolean {
        if (activeRun?.isActive != true) return false
        message(R.string.recordings_run_busy)
        return true
    }

    /** Ends the run: drops the strip by clearing every progress flow and releases [activeRun]. */
    private fun finishRun() {
        activeRun = null
        _exportProgress.value = null
        _batchProgress.value = null
        _serverDownload.value = null
    }

    private val _events = Channel<RecordingsUiEvent>(Channel.BUFFERED)

    /** Export results the screen turns into a snack-bar message or a share sheet. */
    val events: Flow<RecordingsUiEvent> = _events.receiveAsFlow()

    /**
     * Wires the [ExportRunner]'s progress + effect callbacks to this ViewModel's flows and event channel:
     * the runner owns the pipeline, the ViewModel only mirrors what it reports onto the state the screen
     * reads. The share effect is forwarded to the screen (as a [RecordingsUiEvent.Share]) because only an
     * Activity context can open the system sheet — the ViewModel holds none.
     */
    private val exportContext = ExportContext(
        onExportProgress = { percent, bodyRes -> _exportProgress.value = ExportRunProgress(percent, bodyRes) },
        onBatchProgress = { done, total -> _batchProgress.value = done to total },
        onServerDownload = { percent -> _serverDownload.value = percent },
        onMessage = { text, short -> emitMessage(text, short) },
        onShare = { files, metadata -> _events.trySend(RecordingsUiEvent.Share(files, metadata)) },
        onCelebrate = { _events.trySend(RecordingsUiEvent.ExportCompleted) },
    )

    /** Server rows are a lazy one-shot fetch, kept out of the Room flow so an offline server can't stall it. */
    private data class ServerListState(
        val items: List<ServerRecordingDto> = emptyList(),
        val isLoading: Boolean = false
    )
    private val _serverState = MutableStateFlow(ServerListState())

    private data class Filters(val deviceId: Long?, val eventType: EventType?, val query: String)
    private data class SourceData(
        val recordings: List<Recording>,
        val devices: List<Device>,
        val settings: AppSettings?,
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
                        runCatching { EventType.valueOf(dto.eventType) }.getOrDefault(EventType.UNKNOWN) == filters.eventType
                val matchesQuery = filters.query.isBlank() ||
                        dto.deviceName.contains(filters.query, ignoreCase = true) ||
                        (dto.note?.contains(filters.query, ignoreCase = true) == true)
                val video = dto.videoUrl
                if (!matchesDevice || !matchesType || !matchesQuery || video == null) null
                else GalleryItem.Remote(
                    dto = dto,
                    thumbnailAbsoluteUrl = ServerMediaUrls.absolute(settings.serverBaseUrl, dto.thumbnailUrl, settings.serverApiKey),
                    videoAbsoluteUrl = ServerMediaUrls.absolute(settings.serverBaseUrl, video, settings.serverApiKey) ?: ""
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
            isLoading = false,
            filtersActive = filters.deviceId != null || filters.eventType != null || filters.query.isNotBlank(),
            serverModeEnabled = settings?.recordingMode == RecordingMode.PYTHON_SERVER
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordingsUiState())

    /**
     * Fetches the server's completed recordings; called when the screen is shown. A no-op that also clears
     * any stale list outside PYTHON_SERVER mode, so the app never dials a server the user is not using.
     * A failure is reported as a message and leaves whatever was already listed in place.
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
                message(R.string.recordings_toast_server_unreachable, error.message ?: "")
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

    /**
     * Drops all three filters at once, the one action the filtered empty state offers. It also leaves
     * multi-select: a selection counted against the filtered view no longer means the same rows.
     */
    fun clearFilters() {
        _selectedDeviceId.value = null
        _selectedEventType.value = null
        _searchQuery.value = ""
        clearSelection()
        _events.trySend(RecordingsUiEvent.SelectionCleared)
    }

    fun deleteRecording(recording: Recording) {
        // Drop the row from the selection where it still sits, so the header count never names a clip
        // that is already gone and a deleted anchor cannot stretch a range across the list.
        _selectedIds.update { it - recording.id }
        if (selectionAnchorId == recording.id) selectionAnchorId = null
        viewModelScope.launch {
            val result = repository.deleteRecording(recording)
            // A row that quietly vanishes is inferred, not confirmed - and this one took a file with it.
            // Short, because it is a receipt rather than news.
            if (result is RecordingDeleteResult.DeletedWithOrphans) {
                // Said out loud rather than swallowed: the clip is gone from the list, but the space it
                // took is still on the partition until the cleanup sweep gets to it.
                message(R.string.recordings_toast_deleted_orphan, short = true)
            } else {
                message(R.string.recordings_toast_deleted, short = true)
            }
        }
    }

    // --- Multi-select bulk delete ---

    fun toggleSelection(id: Long) {
        _selectedIds.update { if (id in it) it - id else it + id }
        selectionAnchorId = id
    }

    /**
     * Marks every local row between the last picked row and [targetId], inclusive — the "pick the first,
     * pick the last" gesture. Measured on the visible order, so rows a filter hides are never swept in,
     * and it only ever adds: deselecting stays a per-row action.
     */
    fun selectRangeTo(targetId: Long) {
        val visibleIds = uiState.value.localRecordings.map { it.id }
        _selectedIds.update { it + rangeSelection(visibleIds, selectionAnchorId, targetId) }
        selectionAnchorId = targetId
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
        selectionAnchorId = null
    }

    /**
     * What the selection title and the bulk-delete dialog read. The counts run over the whole archive
     * rather than the filtered view, because that is what a bulk operation acts on:
     * [hiddenSelectedCount] says how many checked rows the filters currently hide, so the number on screen
     * never promises a different batch than the one that gets deleted or exported.
     */
    data class SelectionInfo(
        val selectedCount: Int,
        val protectedCount: Int,
        val hiddenSelectedCount: Int = 0,
    )

    val selectionInfo: StateFlow<SelectionInfo> = combine(
        repository.allRecordings,
        uiState,
        _selectedIds
    ) { recordings, state, ids ->
        val selected = recordings.filter { it.id in ids }
        SelectionInfo(
            selectedCount = selected.size,
            protectedCount = selected.count { it.isProtected },
            hiddenSelectedCount = hiddenSelectedCount(ids, state.localRecordings.map { it.id })
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SelectionInfo(0, 0))

    /** Selects every local recording currently visible after filters (server rows are read-only). */
    fun selectAllVisible() {
        _selectedIds.value = uiState.value.localRecordings.map { it.id }.toSet()
    }

    /**
     * Every local recording, filters ignored — what a bulk operation resolves its checked ids against.
     * Reading the screen state instead would let a filter quietly shrink the batch the user confirmed.
     */
    private suspend fun allLocalRecordings(): List<Recording> = repository.allRecordings.first()

    /** Deletes the unprotected selected recordings (and their files), then clears the selection. */
    fun deleteSelected() {
        val ids = _selectedIds.value
        viewModelScope.launch {
            val targets = allLocalRecordings().filter { it.id in ids }
            if (targets.isEmpty()) {
                message(R.string.recordings_toast_nothing_selected)
                return@launch
            }
            // A protected clip survives a bulk delete just like it survives retention cleanup; the user did
            // ask for it, so the skip is reported instead of being dropped silently.
            val (deletable, kept) = targets.partition { !it.isProtected }
            if (kept.isNotEmpty()) {
                messagePlural(R.plurals.recordings_toast_bulk_delete_skipped_protected, kept.size, kept.size)
            }
            if (deletable.isNotEmpty()) {
                val report = repository.deleteRecordings(deletable)
                if (report.leftOrphans) {
                    // One message for the batch: the rows are all gone, and the number of files that
                    // outlived them is the cleanup sweep's to count, not something to read out per clip.
                    message(R.string.recordings_toast_deleted_orphan)
                }
            }
            clearSelection()
        }
    }

    fun toggleProtection(recording: Recording) {
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
                message(R.string.recordings_toast_server_action_failed, error.message ?: "")
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
                message(R.string.recordings_toast_server_deleted, short = true)
            }.onFailure { error ->
                message(R.string.recordings_toast_server_action_failed, error.message ?: "")
            }
        }
    }

    /**
     * Exports one recording through [ExportRunner]; the busy guard and the progress strip's lifecycle stay
     * here — the run owns [activeRun] so a second request is refused, and [finishRun] drops every flow when
     * the run ends or is cancelled.
     */
    fun exportRecording(recording: Recording, kind: RecordingExportKind) {
        if (runBusy()) return
        activeRun = viewModelScope.launch {
            try {
                exportRunner.exportLocal(recording, kind, exportContext)
            } finally {
                finishRun()
            }
        }
    }

    /**
     * Exports every checked recording, whether or not a filter currently hides it, through [ExportRunner]
     * with the same lazy-transcode policy. Leaving multi-select and clearing the selection stay here because
     * the ViewModel owns the selection state.
     */
    fun exportSelected(kind: RecordingExportKind) {
        if (runBusy()) return
        if (_selectedIds.value.isEmpty()) {
            message(R.string.recordings_toast_nothing_selected)
            return
        }
        activeRun = viewModelScope.launch {
            try {
                val targets = allLocalRecordings().filter { it.id in _selectedIds.value }
                exportRunner.exportBatch(targets, kind, exportContext)
            } finally {
                clearSelection()
                _events.trySend(RecordingsUiEvent.SelectionCleared)
                finishRun()
            }
        }
    }

    /**
     * Downloads one server recording and delivers it through [ExportRunner]. The check that the row actually
     * has a video URL stays here so its message precedes the run, exactly as before; the row is reduced to a
     * [ServerRecordingSource] so the runner never sees a UI type.
     */
    fun exportServerRecording(item: GalleryItem.Remote, kind: RecordingExportKind) {
        if (runBusy()) return
        if (item.videoAbsoluteUrl.isBlank()) {
            message(R.string.recordings_toast_download_failed, "no video url")
            return
        }
        val source = ServerRecordingSource(
            id = item.dto.id,
            videoAbsoluteUrl = item.videoAbsoluteUrl,
            deviceName = item.deviceName,
            eventType = item.eventType,
        )
        activeRun = viewModelScope.launch {
            try {
                exportRunner.exportServer(source, kind, exportContext)
            } finally {
                finishRun()
            }
        }
    }

    private fun message(@StringRes id: Int, vararg args: Any, short: Boolean = false) {
        emitMessage(strings(id, *args), short)
    }

    private fun messagePlural(@PluralsRes id: Int, quantity: Int, vararg args: Any) {
        emitMessage(strings.quantity(id, quantity, *args))
    }

    /**
     * The one way a message reaches the screen. Also the sink [ExportGateway] hands its already-built
     * strings to, so a gallery save reports through the same snack bar as everything else.
     */
    private fun emitMessage(text: String, short: Boolean = false) {
        _events.trySend(RecordingsUiEvent.Message(text, short))
    }
}
