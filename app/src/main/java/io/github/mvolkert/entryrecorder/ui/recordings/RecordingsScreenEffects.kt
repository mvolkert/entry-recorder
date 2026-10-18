package io.github.mvolkert.entryrecorder.ui.recordings

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.util.ExportGateway
import io.github.mvolkert.entryrecorder.util.RecordingExportKind
import io.github.mvolkert.entryrecorder.ui.LocalAppSnackbar
import io.github.mvolkert.entryrecorder.ui.components.CelebrationCheck
import io.github.mvolkert.entryrecorder.ui.showMessage
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeColor
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeOnColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The screen-side half of an export: the share sheet, which has to be launched from an Activity context — the
 * one thing the ViewModel deliberately holds no more. Resolves the app-wide [ExportGateway] through a Hilt
 * entry point and returns the dispatch for a [RecordingsUiEvent.Share], so the screen's event collector stays
 * one `LaunchedEffect` with its arms in order.
 *
 * The gateway builds its outcome text on the calling thread and has no coroutine to post from, so this closes
 * over the hop into the snack bar. The single-file sheet needs the Activity context (it adds no
 * `FLAG_ACTIVITY_NEW_TASK`); the batch sheet takes the same files.
 */
@Composable
internal fun rememberShareLauncher(): (RecordingsUiEvent.Share) -> Unit {
    val context = LocalContext.current
    val gateway = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, RecordingsScreenEntryPoint::class.java).exportGateway()
    }
    val snackbarHost = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    return { event ->
        val metadata = event.metadata
        val file = event.files.firstOrNull()
        val onShareMessage: (String) -> Unit = { text -> scope.launch { snackbarHost.showMessage(text) } }
        if (metadata != null && file != null) {
            gateway.shareFile(context, file, metadata, onShareMessage)
        } else {
            gateway.shareFiles(context, event.files, onShareMessage)
        }
    }
}

/**
 * The screen's half of the ViewModel's one-shot events: the snack bar hop, the share sheet, and the two flags
 * the gallery owns. Kept as one `LaunchedEffect(Unit)` so the queue order is unchanged - a batch that reports
 * skipped protected clips and then a saved count delivers both instead of the last one winning.
 *
 * **Compose this unconditionally**, never behind an `if`: the collector has to stay alive for the whole time
 * the screen is, or an event that lands while a branch is hidden is lost.
 */
@Composable
internal fun LaunchedRecordingsEvents(
    viewModel: RecordingsViewModel,
    share: (RecordingsUiEvent.Share) -> Unit,
    onSelectionCleared: () -> Unit,
    onExportCompleted: () -> Unit,
) {
    val snackbarHost = LocalAppSnackbar.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is RecordingsUiEvent.Message -> snackbarHost.showMessage(event.text, event.short)

                is RecordingsUiEvent.Share -> share(event)

                RecordingsUiEvent.SelectionCleared -> onSelectionCleared()

                RecordingsUiEvent.ExportCompleted -> onExportCompleted()
            }
        }
    }
}

/** The state and the two clear actions of the gallery search band; built by [rememberRecordingsSearchBand]. */
internal class RecordingsSearchBand(
    val searchBarState: SearchBarState,
    val textFieldState: TextFieldState,
    val clearSearchQuery: () -> Unit,
    val clearAllFilters: () -> Unit,
)

/**
 * Owns the Material search band of the Recordings tab: the expanded/collapsed state, the query field, and the
 * two effects that tie them to the ViewModel.
 *
 * In material3 beta01 the collapsed [androidx.compose.material3.SearchBar] wraps its field in an input
 * interceptor that never resolves, so the trigger only opens the surface and the surface owns the keyboard —
 * see RecordingsSearchBar.kt.
 *
 * **Compose this unconditionally**, never behind an `if`: it runs `LaunchedEffect`s, and an effect that appears
 * or disappears with a branch restarts, which would re-run the one-way query bridge mid-typing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberRecordingsSearchBand(
    viewModel: RecordingsViewModel,
    selectionMode: Boolean,
    query: String,
): RecordingsSearchBand {
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState(initialText = query)
    // The ViewModel stays the single source of truth and the bridge runs one way, field up to state: a
    // filter state that lags a keystroke must never write back and eat the character just typed.
    LaunchedEffect(Unit) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect { viewModel.setSearchQuery(it) }
    }
    val clearSearchQuery: () -> Unit = { textFieldState.clearText() }
    // The ViewModel's clearFilters also drops the query, and nothing pushes state back down to the field,
    // so the field follows by hand or it would keep showing the text that just stopped filtering.
    val clearAllFilters: () -> Unit = {
        viewModel.clearFilters()
        clearSearchQuery()
    }
    // Multi-select owns the top band, and a surface that covers the window hides the check marks it is
    // there to show; entering it puts the search away first.
    LaunchedEffect(selectionMode) {
        if (selectionMode) searchBarState.animateToCollapsed()
    }
    return RecordingsSearchBand(searchBarState, textFieldState, clearSearchQuery, clearAllFilters)
}

/**
 * Exports this row through [viewModel]: a local clip straight out of the phone's storage, a server row by
 * downloading it first. The raw-folder kind has no branch here on purpose — a raw copy only exists for local
 * clips, and that is what hides the menu entry on a server row.
 */
internal fun GalleryItem.exportAs(kind: RecordingExportKind, viewModel: RecordingsViewModel) {
    when (this) {
        is GalleryItem.Local -> viewModel.exportRecording(recording, kind)
        is GalleryItem.Remote -> viewModel.exportServerRecording(this, kind)
    }
}

/**
 * The progress strip for whichever export or download run is going, plus the celebration check a finished
 * export leaves behind, both floating over the top of the list exactly like each other: starting, ending or
 * dismissing either never shifts a row, and the list stays readable while it works.
 *
 * An extension of [BoxScope] so `align` keeps resolving against the same box the gallery draws in, with the
 * padding it always had. The batch run has no single percent — its label already reads "k of n".
 */
@Composable
internal fun BoxScope.GalleryRunOverlay(
    exportProgress: ExportRunProgress?,
    batchProgress: Pair<Int, Int>?,
    serverDownload: Int?,
    showSavedCheck: Boolean,
    onDismissSavedCheck: () -> Unit,
    onCancelRun: () -> Unit,
) {
    val activeRun = exportProgress?.let { run ->
        RunDisplay(percent = run.percent, label = stringResource(run.bodyRes, run.percent))
    } ?: batchProgress?.let { (done, total) ->
        RunDisplay(
            percent = null,
            label = stringResource(R.string.recordings_batch_progress_body, done, total),
        )
    } ?: serverDownload?.let { percent ->
        RunDisplay(
            percent = percent,
            label = stringResource(R.string.recordings_download_progress_body, percent),
        )
    }
    RunProgressStrip(
        run = activeRun,
        onCancel = onCancelRun,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.sm),
    )

    // Both export origins (the card menus and the bulk bar) sit above this area, and the manual green is the
    // contrast-checked pair the record-stop celebration uses too — one hue means "done" in both places.
    CelebrationCheck(
        visible = showSavedCheck,
        onDismiss = onDismissSavedCheck,
        containerColor = eventTypeColor(EventType.MANUAL),
        contentColor = eventTypeOnColor(EventType.MANUAL),
        message = stringResource(R.string.recordings_celebration_saved),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = Spacing.sm),
        size = 24.dp,
        iconSize = 16.dp,
    )
}

/**
 * A Hilt accessor so the effects helper can reach the app-wide [ExportGateway] to run the share sheet from the
 * screen's own Activity context. The ViewModel deliberately holds no gateway (and no Context) now that the
 * share is a screen-side effect, so the screen — not the ViewModel — is the one that talks to the gateway.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
private interface RecordingsScreenEntryPoint {
    fun exportGateway(): ExportGateway
}
