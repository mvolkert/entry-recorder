package io.github.mvolkert.entryrecorder.ui.recordings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.domain.model.Recording
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.util.ExportGateway
import io.github.mvolkert.entryrecorder.util.RecordingExportKind
import io.github.mvolkert.entryrecorder.ui.LocalAppSnackbar
import io.github.mvolkert.entryrecorder.ui.showMessage
import io.github.mvolkert.entryrecorder.ui.components.CelebrationCheck
import io.github.mvolkert.entryrecorder.ui.components.ExpressiveIconBadge
import io.github.mvolkert.entryrecorder.ui.components.LoadingSpot
import io.github.mvolkert.entryrecorder.ui.components.PlaybackTarget
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeColor
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeOnColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    /** Hands a clip to the window-level player layer, which the app root draws over the navigation scaffold. */
    onRequestPlayback: (PlaybackTarget) -> Unit = {},
    viewModel: RecordingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The share sheet has to be launched from this screen's Activity context — the one thing the ViewModel
    // can no longer hold — so the screen reaches the app-wide [ExportGateway] directly and owns that step.
    val exportGateway = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, RecordingsScreenEntryPoint::class.java).exportGateway()
    }
    val snackbarHost = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val exportProgress by viewModel.exportProgress.collectAsStateWithLifecycle()
    val batchProgress by viewModel.batchProgress.collectAsStateWithLifecycle()
    val serverDownload by viewModel.serverDownload.collectAsStateWithLifecycle()
    var recordingToDelete by remember { mutableStateOf<Recording?>(null) }
    var serverItemToDelete by remember { mutableStateOf<GalleryItem.Remote?>(null) }
    // Set by the ViewModel when an export wrote its files; the check floats over the list and clears itself.
    var showSavedCheck by remember { mutableStateOf(false) }

    // The clip itself lives above this scaffold in the app root, which ends it when the pager moves; all
    // that is owned here is the celebration check, which must not wait behind an inactive page.
    LaunchedEffect(active) {
        if (!active) showSavedCheck = false
    }

    // Server recordings are a lazy one-shot fetch, refreshed when the screen is entered (a no-op outside
    // PYTHON_SERVER mode). Local Room data drives the list immediately and never blocks on the network.
    LaunchedEffect(Unit) {
        viewModel.refreshServerRecordings()
    }

    // Multi-select mode; a tap toggles one row, a long-press marks a range between the last pick and the
    // pressed row, and the ViewModel owns which rows are checked.
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val selection by viewModel.selectionInfo.collectAsStateWithLifecycle()
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    // One exit for both ways out of multi-select: the bar's X and the system back gesture. Back has to be
    // taken over or it leaves the tab armed behind the pager, check marks and all.
    val exitSelection = {
        selectionMode = false
        viewModel.clearSelection()
    }
    BackHandler(enabled = selectionMode) { exitSelection() }

    // Search is the Material search band rather than a text-field pill. In material3 beta01 the collapsed
    // [androidx.compose.material3.SearchBar] wraps its field in an input interceptor that never resolves,
    // so the trigger only opens the surface and the surface owns the keyboard - see RecordingsSearchBar.kt.
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState(initialText = state.searchQuery)
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

    // Export runs in the ViewModel; its results arrive as one-shot events. This screen supplies only its
    // own Activity context to the share sheet, which is the one step that cannot run from the application.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                // Awaiting the message keeps the queue in order: a batch that reports skipped protected
                // clips and then a saved count delivers both instead of the last one winning.
                is RecordingsUiEvent.Message -> snackbarHost.showMessage(event.text, event.short)

                is RecordingsUiEvent.Share -> {
                    // ExportGateway builds its outcome text on this thread and has no coroutine to post
                    // from, so the screen hands it the hop into the snack bar. The single-file sheet needs
                    // this Activity context (it adds no NEW_TASK); the batch sheet takes the same files.
                    val metadata = event.metadata
                    val file = event.files.firstOrNull()
                    val onShareMessage: (String) -> Unit = { text -> scope.launch { snackbarHost.showMessage(text) } }
                    if (metadata != null && file != null) {
                        exportGateway.shareFile(context, file, metadata, onShareMessage)
                    } else {
                        exportGateway.shareFiles(context, event.files, onShareMessage)
                    }
                }

                RecordingsUiEvent.SelectionCleared -> selectionMode = false

                RecordingsUiEvent.ExportCompleted -> showSavedCheck = true
            }
        }
    }

    // The scroll position belongs to the list, not to whatever container wraps it: the gallery renders
    // inside a pull-to-refresh box only in server mode, and hoisting the state keeps the position across
    // that flip - and keeps the rows themselves out of the branch below.
    val listState = rememberLazyListState()

    // Every clip is one row of the same list: no hero above the newest item, so the gallery reads as a
    // uniform archive and each row keeps the same tap, long-press and menu targets. The rows are hoisted
    // because the tab list and the expanded search surface render exactly the same filtered clips.
    // animateItem()'s own defaults are framework springs that ignore the scheme, so all three phases name
    // the motion scheme's specs (Design.md §4).
    val motion = MaterialTheme.motionScheme
    val rows: LazyListScope.() -> Unit = {
        items(state.items, key = { it.stableKey }) { item ->
            val localId = (item as? GalleryItem.Local)?.recording?.id
            RecordingCardItem(
                modifier = Modifier.animateItem(
                    fadeInSpec = motion.defaultEffectsSpec(),
                    placementSpec = motion.defaultSpatialSpec(),
                    fadeOutSpec = motion.defaultEffectsSpec(),
                ),
                item = item,
                selectionMode = selectionMode,
                selected = item is GalleryItem.Local && item.recording.id in selectedIds,
                onSelectToggle = {
                    (item as? GalleryItem.Local)?.let { viewModel.toggleSelection(it.recording.id) }
                },
                onLongSelect = {
                    localId?.let { id ->
                        if (selectionMode) {
                            viewModel.selectRangeTo(id)
                        } else {
                            selectionMode = true
                            viewModel.toggleSelection(id)
                        }
                    }
                },
                onPlay = {
                    onRequestPlayback(
                        when (item) {
                            is GalleryItem.Local -> PlaybackTarget.LocalFile(item.recording.filePath)
                            is GalleryItem.Remote -> PlaybackTarget.RemoteUrl(item.videoAbsoluteUrl)
                        }
                    )
                },
                onDelete = {
                    when (item) {
                        is GalleryItem.Local -> recordingToDelete = item.recording
                        is GalleryItem.Remote -> serverItemToDelete = item
                    }
                },
                onToggleProtect = {
                    when (item) {
                        is GalleryItem.Local -> viewModel.toggleProtection(item.recording)
                        is GalleryItem.Remote -> viewModel.toggleServerProtection(item)
                    }
                },
                onShare = {
                    when (item) {
                        is GalleryItem.Local -> viewModel.exportRecording(item.recording, RecordingExportKind.SHARE)
                        is GalleryItem.Remote -> viewModel.exportServerRecording(item, RecordingExportKind.SHARE)
                    }
                },
                onExportGallery = {
                    when (item) {
                        is GalleryItem.Local -> viewModel.exportRecording(item.recording, RecordingExportKind.GALLERY)
                        is GalleryItem.Remote -> viewModel.exportServerRecording(item, RecordingExportKind.GALLERY)
                    }
                },
                onExportFolder = {
                    when (item) {
                        is GalleryItem.Local -> viewModel.exportRecording(item.recording, RecordingExportKind.FOLDER)
                        is GalleryItem.Remote -> viewModel.exportServerRecording(item, RecordingExportKind.FOLDER)
                    }
                },
                // Raw copies only exist for local clips; server rows already live on the server as H.264.
                onExportRawFolder = if (item is GalleryItem.Local) {
                    { viewModel.exportRecording(item.recording, RecordingExportKind.RAW_FOLDER) }
                } else null
            )
        }
    }

    Scaffold(
        // Top inset is consumed by the TopAppBar itself, exactly like the Live and Settings tabs;
        // bottom system inset comes from the host NavigationBar in MainActivity. Nested Scaffold
        // stays inset-free to avoid double-counting. The strip under the bar is padded by hand:
        // TopAppBar must keep its own horizontal content padding, so nothing wraps it.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                RecordingsTopBar(
                    selectionMode = selectionMode,
                    selectedCount = selection.selectedCount,
                    hiddenSelectedCount = selection.hiddenSelectedCount,
                    onSelectAll = { viewModel.selectAllVisible() },
                    onExportSelected = { kind -> viewModel.exportSelected(kind) },
                    onBulkDelete = { showBulkDeleteConfirm = true },
                    onExitSelection = exitSelection,
                    onEnterSelection = { selectionMode = true }
                )

                Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                    // Multi-select hands the band its own actions; a second row of controls there is
                    // noise, and the search surface would cover the rows being checked anyway.
                    if (!selectionMode) {
                        RecordingsSearchTrigger(
                            searchBarState = searchBarState,
                            textFieldState = textFieldState,
                            query = state.searchQuery,
                            onClearQuery = clearSearchQuery,
                            onSearch = { scope.launch { searchBarState.animateToCollapsed() } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                    }
                    RecordingsStorageLine(totalStorageBytes = state.totalStorageBytes)
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    RecordingsFilterBar(
                        selectedEventType = state.selectedEventType,
                        onEventTypeSelect = { viewModel.selectEventTypeFilter(it) },
                        devices = state.devices,
                        selectedDeviceId = state.selectedDeviceId,
                        onDeviceSelect = { viewModel.selectDeviceFilter(it) }
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            RecordingsGallery(
                state = state,
                listState = listState,
                rows = rows,
                onClearFilters = clearAllFilters,
                onRefresh = { viewModel.refreshServerRecordings() },
            )

            // One strip for whichever run is going — the transcode/copy percent, the k-of-n batch count or
            // the server download — floating over the top of the list exactly like the celebration check, so
            // starting or ending a run never shifts a row and the list stays readable while it works.
            val activeRun = exportProgress?.let { run ->
                RunDisplay(percent = run.percent, label = stringResource(run.bodyRes, run.percent))
            } ?: batchProgress?.let { (done, total) ->
                // The batch has no single percent: its label already reads "k of n".
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
                onCancel = { viewModel.cancelActiveRun() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.sm),
            )

            // A finished export celebrates over the top of the list instead of joining it, so the check
            // appearing and leaving never shifts a row. Both export origins (the card menus and the bulk
            // bar) sit above this area, and the manual green is the contrast-checked pair the record-stop
            // celebration uses too — one hue means "done" in both places.
            CelebrationCheck(
                visible = showSavedCheck,
                onDismiss = { showSavedCheck = false },
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
    }

    // The expanded half of the search band: a surface over the window that owns the keyboard and shows the
    // same rows, filtered as the query changes. Composed unconditionally - the search bar state gates it
    // internally - and with its own scroll state, because one LazyListState cannot be attached to this
    // sheet and the tab list at the same time while the surface animates open.
    RecordingsSearchSurface(
        searchBarState = searchBarState,
        textFieldState = textFieldState,
        query = state.searchQuery,
        onClearQuery = clearSearchQuery,
        onSearch = { scope.launch { searchBarState.animateToCollapsed() } },
    ) {
        RecordingsGallery(
            state = state,
            listState = rememberLazyListState(),
            rows = rows,
            onClearFilters = clearAllFilters,
            onRefresh = { viewModel.refreshServerRecordings() },
            modifier = Modifier.weight(1f),
        )
    }

    recordingToDelete?.let { rec ->
        DeleteRecordingDialog(
            deviceName = rec.deviceName,
            timestamp = rec.timestamp,
            onDismiss = { recordingToDelete = null },
            onConfirm = {
                viewModel.deleteRecording(rec)
                recordingToDelete = null
            }
        )
    }

    serverItemToDelete?.let { item ->
        DeleteRecordingDialog(
            deviceName = item.deviceName,
            timestamp = item.timestamp,
            onDismiss = { serverItemToDelete = null },
            onConfirm = {
                viewModel.deleteServerRecording(item)
                serverItemToDelete = null
            }
        )
    }

    if (showBulkDeleteConfirm) {
        BulkDeleteDialog(
            selectedCount = selection.selectedCount,
            protectedCount = selection.protectedCount,
            onDismiss = { showBulkDeleteConfirm = false },
            onConfirm = {
                viewModel.deleteSelected()
                showBulkDeleteConfirm = false
                selectionMode = false
            }
        )
    }
}

/**
 * The list area of the Recordings tab: a spinner, the empty archive, or the rows. The tab body and the
 * expanded search surface both render it, so a search never shows a different gallery from the tab.
 * [listState] comes from the caller - the surface passes its own, see [RecordingsScreen].
 */
@Composable
private fun RecordingsGallery(
    state: RecordingsUiState,
    listState: LazyListState,
    rows: LazyListScope.() -> Unit,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (state.isLoading) {
            LoadingSpot(modifier = Modifier.align(Alignment.Center))
        } else if (state.items.isEmpty()) {
            RecordingsEmptyState(filtersActive = state.filtersActive, onClearFilters = onClearFilters)
        } else if (state.serverModeEnabled) {
            // Pull-to-refresh re-fetches the server list, so the gesture is only offered where there is
            // something to fetch. The material3 box has no enable flag, so the container itself is chosen
            // and both branches hand the same [rows] to the same [listState].
            PullToRefreshBox(
                isRefreshing = state.isServerLoading,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                GalleryList(listState = listState, content = rows)
            }
        } else {
            GalleryList(listState = listState, content = rows)
        }
    }
}

/** Nothing found under a filter is a different fact from an empty archive, and it needs a different action. */
@Composable
private fun RecordingsEmptyState(filtersActive: Boolean, onClearFilters: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ExpressiveIconBadge(
                icon = if (filtersActive) Icons.Default.FilterList else Icons.Default.VideoLibrary,
                contentDescription = null,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = stringResource(
                    if (filtersActive) R.string.recordings_filtered_empty else R.string.recordings_empty
                ),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(
                    if (filtersActive) R.string.recordings_filtered_empty_hint
                    else R.string.recordings_empty_hint
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (filtersActive) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                TextButton(onClick = onClearFilters) {
                    Text(stringResource(R.string.recordings_clear_filters))
                }
            }
        }
    }
}

/**
 * The gallery list itself, so the pull-to-refresh branch above wraps the very same composable rather than
 * a copy of its measurements. [listState] comes from the caller, which is what keeps the scroll position
 * when the mode flips.
 */
@Composable
private fun GalleryList(
    listState: LazyListState,
    content: LazyListScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content
    )
}

/**
 * A Hilt accessor so this composable can reach the app-wide [ExportGateway] to run the share sheet from
 * its own Activity context. The ViewModel deliberately holds no gateway (and no Context) now that the
 * share is a screen-side effect, so the screen — not the ViewModel — is the one that talks to the gateway.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
private interface RecordingsScreenEntryPoint {
    fun exportGateway(): ExportGateway
}
