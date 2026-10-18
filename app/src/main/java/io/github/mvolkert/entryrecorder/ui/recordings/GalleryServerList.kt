package io.github.mvolkert.entryrecorder.ui.recordings

import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.data.server.ServerClient
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingDto
import io.github.mvolkert.entryrecorder.util.UiStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The server half of the merged gallery: the list of completed server recordings and the two mutations that
 * write back to it.
 *
 * Extracted from [RecordingsViewModel], which keeps deciding *when* the list is asked for and how a row becomes
 * a [GalleryItem]. The fetch stays out of the Room flow on purpose: an offline server must not stall the local
 * list, so the rows arrive as their own state rather than as another repository stream, and a failed request
 * leaves whatever was already listed in place. [scope] is the ViewModel's, so an in-flight request cannot
 * outlive the screen.
 */
class GalleryServerList(
    private val scope: CoroutineScope,
    private val repository: IntercomRepository,
    private val serverClient: ServerClient,
    private val strings: UiStrings,
    private val emitMessage: (text: String, short: Boolean) -> Unit,
) {
    /** Server rows are a lazy one-shot fetch kept out of the Room flow so an offline server can't stall it. */
    data class State(
        val items: List<ServerRecordingDto> = emptyList(),
        val isLoading: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Fetches the server's completed recordings; called when the screen is shown. A no-op that also clears any
     * stale list outside PYTHON_SERVER mode, so the app never dials a server the user is not using.
     */
    fun refresh() {
        scope.launch {
            val settings = repository.getSettings()
            if (settings.recordingMode != RecordingMode.PYTHON_SERVER) {
                _state.value = State()
                return@launch
            }
            _state.value = _state.value.copy(isLoading = true)
            serverClient.listRecordings(
                serverUrl = settings.serverBaseUrl,
                apiKey = settings.serverApiKey.ifBlank { null }
            ).onSuccess { list ->
                _state.value = State(items = list)
            }.onFailure { error ->
                _state.value = _state.value.copy(isLoading = false)
                emitMessage(strings(R.string.recordings_toast_server_unreachable, error.message ?: ""), false)
            }
        }
    }

    /** Flips the server-side protection flag and reflects the new state in the listed row on success. */
    fun setProtected(dto: ServerRecordingDto) {
        val newProtected = !dto.isProtected
        scope.launch {
            val settings = repository.getSettings()
            serverClient.setProtected(
                serverUrl = settings.serverBaseUrl,
                apiKey = settings.serverApiKey.ifBlank { null },
                recordingId = dto.id,
                isProtected = newProtected
            ).onSuccess {
                _state.update { state ->
                    state.copy(items = state.items.map { if (it.id == dto.id) it.copy(isProtected = newProtected) else it })
                }
            }.onFailure { error ->
                emitMessage(strings(R.string.recordings_toast_server_action_failed, error.message ?: ""), false)
            }
        }
    }

    /** Deletes a completed recording on the server and drops it from the list on success. */
    fun delete(dto: ServerRecordingDto) {
        scope.launch {
            val settings = repository.getSettings()
            serverClient.deleteRecording(
                serverUrl = settings.serverBaseUrl,
                apiKey = settings.serverApiKey.ifBlank { null },
                recordingId = dto.id
            ).onSuccess {
                _state.update { state -> state.copy(items = state.items.filterNot { it.id == dto.id }) }
                emitMessage(strings(R.string.recordings_toast_server_deleted), true)
            }.onFailure { error ->
                emitMessage(strings(R.string.recordings_toast_server_action_failed, error.message ?: ""), false)
            }
        }
    }
}
