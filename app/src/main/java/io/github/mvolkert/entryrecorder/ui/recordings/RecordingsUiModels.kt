package io.github.mvolkert.entryrecorder.ui.recordings

import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.domain.model.Recording
import io.github.mvolkert.entryrecorder.util.ExportMetadata
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
