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
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.util.ExportHelper
import io.github.mvolkert.entryrecorder.video.ExportTranscoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

/** Where a manually triggered export delivers the file. */
enum class RecordingExportKind { SHARE, GALLERY, FOLDER }

data class RecordingsUiState(
    val recordings: List<RecordingEntity> = emptyList(),
    val devices: List<DeviceEntity> = emptyList(),
    val selectedDeviceId: Long? = null,
    val selectedEventType: EventType? = null,
    val searchQuery: String = "",
    val totalStorageBytes: Long = 0,
    val isLoading: Boolean = true
)

/** One-shot export effects the screen has to perform (toasts, the share sheet). */
sealed interface RecordingsUiEvent {
    data class Message(val text: String, val short: Boolean = false) : RecordingsUiEvent

    /**
     * The share sheet has to be launched from an Activity context (the single-file path does not add
     * `FLAG_ACTIVITY_NEW_TASK`), so the ViewModel resolves the files and the screen performs the launch.
     * [recording] is non-null for a single-file share, null for the multi-select batch.
     */
    data class Share(val files: List<File>, val recording: RecordingEntity?) : RecordingsUiEvent

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

    /** Whether exports should be transcoded to H.264 for universal playback (Settings-driven). */
    val transcodeOnExport: StateFlow<Boolean> = repository.settingsFlow
        .map { it?.transcodeOnExport ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** SAF tree URI of the user-chosen export folder ("" = not set). Drives "Export to folder". */
    val exportFolderUri: StateFlow<String> = repository.settingsFlow
        .map { it?.exportFolderUri ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** Percent (0..100) of the running single-item transcode, null while idle. */
    private val _exportProgress = MutableStateFlow<Int?>(null)
    val exportProgress: StateFlow<Int?> = _exportProgress.asStateFlow()

    /** Done/total pair of a running multi-select export, null while idle. */
    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val batchProgress: StateFlow<Pair<Int, Int>?> = _batchProgress.asStateFlow()

    private val _events = Channel<RecordingsUiEvent>(Channel.BUFFERED)

    /** Export results the screen turns into toasts or a share sheet. */
    val events: Flow<RecordingsUiEvent> = _events.receiveAsFlow()

    private val context: Context get() = getApplication()

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

    // --- Multi-select bulk delete ---

    fun toggleSelection(id: Long) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    /** Selects every recording currently visible after filters. */
    fun selectAllVisible() {
        _selectedIds.value = uiState.value.recordings.map { it.id }.toSet()
    }

    /** Deletes the selected recordings (and their files), then clears the selection. */
    fun deleteSelected() {
        val ids = _selectedIds.value
        val targets = uiState.value.recordings.filter { it.id in ids }
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

    // --- Manual export (lazy transcode) ---

    /**
     * Exports one recording to [kind]'s destination, transcoding the raw MJPEG MKV to H.264 first when
     * the Settings toggle allows it so the result plays in other apps. Transcoding runs only here, on an
     * explicit user action, never during capture. SHARE opens the system sheet and persists nothing;
     * FOLDER writes one file per recording (the re-encode when it exists, otherwise the original), so
     * the lossless original stays in-app and the folder isn't cluttered with near-duplicates.
     */
    fun exportRecording(recording: RecordingEntity, kind: RecordingExportKind) {
        val src = File(recording.filePath)
        val willTranscode = transcodeOnExport.value && src.extension.equals("mkv", ignoreCase = true)

        if (kind == RecordingExportKind.FOLDER) {
            if (exportFolderUri.value.isBlank()) {
                toast(R.string.recordings_toast_set_folder_first)
                return
            }
            if (!src.exists()) {
                toast(R.string.recordings_toast_file_not_found, short = true)
                return
            }
        }
        // Raw file, no progress dialog: the path taken when transcoding is off or the source is not MKV.
        if (!willTranscode && kind != RecordingExportKind.FOLDER) {
            deliver(recording, src, kind)
            return
        }

        viewModelScope.launch {
            try {
                if (willTranscode) _exportProgress.value = 0
                val out = if (willTranscode) transcodeWithProgress(recording) else src
                _exportProgress.value = null
                deliver(recording, out, kind)
            } catch (e: CancellationException) {
                _exportProgress.value = null
                throw e
            } catch (e: Exception) {
                _exportProgress.value = null
                toast(R.string.recordings_toast_export_failed, e.message ?: "")
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
        val targets = uiState.value.recordings.filter { it.id in _selectedIds.value }
        if (targets.isEmpty()) {
            toast(R.string.recordings_toast_nothing_selected)
            return
        }
        if (kind == RecordingExportKind.FOLDER && exportFolderUri.value.isBlank()) {
            toast(R.string.recordings_toast_set_folder_first)
            return
        }
        _batchProgress.value = 0 to targets.size
        viewModelScope.launch {
            val toShare = ArrayList<File>()
            var saved = 0
            var done = 0
            try {
                for (rec in targets) {
                    val src = File(rec.filePath)
                    val willTranscode = transcodeOnExport.value &&
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
                            if (ExportHelper.saveFileToGallery(context, out, rec, showToast = false)) saved++

                        RecordingExportKind.FOLDER -> {
                            // One file per recording (H.264 when produced, else original) — as single-item.
                            val treeUri = exportFolderUri.value.toUri()
                            if (ExportHelper.saveFileToSafFolder(context, treeUri, out, out.name)) saved++
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

                    RecordingExportKind.FOLDER -> toastPlural(
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
            _exportProgress.value = if (total > 0) done * 100 / total else 0
        }

    private fun deliver(recording: RecordingEntity, file: File, kind: RecordingExportKind) {
        when (kind) {
            RecordingExportKind.SHARE -> _events.trySend(RecordingsUiEvent.Share(listOf(file), recording))

            RecordingExportKind.GALLERY -> ExportHelper.saveFileToGallery(context, file, recording)

            RecordingExportKind.FOLDER -> {
                val treeUri = exportFolderUri.value.toUri()
                if (ExportHelper.saveFileToSafFolder(context, treeUri, file, file.name)) {
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
