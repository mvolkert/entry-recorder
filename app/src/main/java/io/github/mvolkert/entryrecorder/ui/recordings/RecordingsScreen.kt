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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.ui.LocalAppSnackbar
import io.github.mvolkert.entryrecorder.ui.showMessage
import io.github.mvolkert.entryrecorder.ui.components.CelebrationCheck
import io.github.mvolkert.entryrecorder.ui.components.ExpressiveIconBadge
import io.github.mvolkert.entryrecorder.ui.components.LoadingSpot
import io.github.mvolkert.entryrecorder.ui.components.PlaybackTarget
import io.github.mvolkert.entryrecorder.ui.components.VideoPlayerModal
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeColor
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeOnColor
import io.github.mvolkert.entryrecorder.util.ExportHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onFullscreenPlayback: (Boolean) -> Unit = {},
    viewModel: RecordingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val exportProgress by viewModel.exportProgress.collectAsStateWithLifecycle()
    val batchProgress by viewModel.batchProgress.collectAsStateWithLifecycle()
    val serverDownload by viewModel.serverDownload.collectAsStateWithLifecycle()
    var activePlayback by remember { mutableStateOf<PlaybackTarget?>(null) }
    var recordingToDelete by remember { mutableStateOf<RecordingEntity?>(null) }
    var serverItemToDelete by remember { mutableStateOf<GalleryItem.Remote?>(null) }
    // Set by the ViewModel when an export wrote its files; the check floats over the list and clears itself.
    var showSavedCheck by remember { mutableStateOf(false) }

    // The player fills the window instead of sitting above the navigation bar, and leaves composition
    // without a dismiss callback (tab swiped away, process restore), so the chrome is restored there too.
    // The tap and the close button write the flag themselves, in the same snapshot as activePlayback: this
    // screen's own layout is what the bar is padding, so a late flip here would resize the overlay one
    // frame into its entrance and the whole player visibly jumps. Flipping only after the fade would be
    // worse still - the video itself would jump by the bar height while it is fully opaque.
    LaunchedEffect(activePlayback) { onFullscreenPlayback(activePlayback != null) }
    DisposableEffect(Unit) { onDispose { onFullscreenPlayback(false) } }

    // All pager pages stay composed, so a player left open while swiping to another tab would keep the
    // window fullscreen and the navigation bar hidden behind a page nobody is looking at. Playback is
    // this screen's own state, so leaving the page ends it.
    LaunchedEffect(active) {
        if (!active) {
            activePlayback = null
            showSavedCheck = false
        }
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

    // Export runs in the ViewModel; its results arrive as one-shot events. The share sheet is launched
    // here because ExportHelper.shareFile needs an Activity context (it adds no NEW_TASK flag).
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                // Awaiting the message keeps the queue in order: a batch that reports skipped protected
                // clips and then a saved count delivers both instead of the last one winning.
                is RecordingsUiEvent.Message -> snackbarHost.showMessage(event.text, event.short)

                is RecordingsUiEvent.Share -> {
                    val recording = event.recording
                    val file = event.files.firstOrNull()
                    // ExportHelper builds its outcome text on this thread and has no coroutine to post
                    // from, so the screen hands it the hop into the snack bar.
                    val post: (String) -> Unit = { text -> scope.launch { snackbarHost.showMessage(text) } }
                    when {
                        recording != null && file != null ->
                            ExportHelper.shareFile(context, file, recording, onMessage = post)
                        event.deviceName != null && file != null ->
                            ExportHelper.shareFile(context, file, event.deviceName, event.eventTypeLabel ?: "", onMessage = post)
                        else -> ExportHelper.shareFiles(context, event.files, onMessage = post)
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
                    RecordingsStorageLine(totalStorageBytes = state.totalStorageBytes)
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    RecordingsFilterBar(
                        searchQuery = state.searchQuery,
                        onSearchQueryChange = { viewModel.setSearchQuery(it) },
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
            if (state.isLoading) {
                LoadingSpot(modifier = Modifier.align(Alignment.Center))
            } else if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Nothing found under a filter is a different fact from an empty archive, and it
                        // needs a different action: the filters are cleared, not the recording workflow.
                        ExpressiveIconBadge(
                            icon = if (state.filtersActive) Icons.Default.FilterList else Icons.Default.VideoLibrary,
                            contentDescription = null,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            text = stringResource(
                                if (state.filtersActive) R.string.recordings_filtered_empty
                                else R.string.recordings_empty
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(
                                if (state.filtersActive) R.string.recordings_filtered_empty_hint
                                else R.string.recordings_empty_hint
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.filtersActive) {
                            Spacer(modifier = Modifier.height(Spacing.sm))
                            TextButton(onClick = { viewModel.clearFilters() }) {
                                Text(stringResource(R.string.recordings_clear_filters))
                            }
                        }
                    }
                }
            } else {
                // Every clip is one row of the same list: no hero above the newest item, so the gallery
                // reads as a uniform archive and each row keeps the same tap, long-press and menu targets.
                val rows: LazyListScope.() -> Unit = {
                    items(state.items, key = { it.stableKey }) { item ->
                        val localId = (item as? GalleryItem.Local)?.entity?.id
                        RecordingCardItem(
                            modifier = Modifier.animateItem(),
                            item = item,
                            selectionMode = selectionMode,
                            selected = item is GalleryItem.Local && item.entity.id in selectedIds,
                            onSelectToggle = {
                                (item as? GalleryItem.Local)?.let { viewModel.toggleSelection(it.entity.id) }
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
                                // Hide the chrome in the same event that opens the player, so the overlay's
                                // first composed frame is already the full window (see the flag's effect above).
                                onFullscreenPlayback(true)
                                activePlayback = when (item) {
                                    is GalleryItem.Local -> PlaybackTarget.LocalFile(item.entity.filePath)
                                    is GalleryItem.Remote -> PlaybackTarget.RemoteUrl(item.videoAbsoluteUrl)
                                }
                            },
                            onDelete = {
                                when (item) {
                                    is GalleryItem.Local -> recordingToDelete = item.entity
                                    is GalleryItem.Remote -> serverItemToDelete = item
                                }
                            },
                            onToggleProtect = {
                                when (item) {
                                    is GalleryItem.Local -> viewModel.toggleProtection(item.entity)
                                    is GalleryItem.Remote -> viewModel.toggleServerProtection(item)
                                }
                            },
                            onShare = {
                                when (item) {
                                    is GalleryItem.Local -> viewModel.exportRecording(item.entity, RecordingExportKind.SHARE)
                                    is GalleryItem.Remote -> viewModel.exportServerRecording(item, RecordingExportKind.SHARE)
                                }
                            },
                            onExportGallery = {
                                when (item) {
                                    is GalleryItem.Local -> viewModel.exportRecording(item.entity, RecordingExportKind.GALLERY)
                                    is GalleryItem.Remote -> viewModel.exportServerRecording(item, RecordingExportKind.GALLERY)
                                }
                            },
                            onExportFolder = {
                                when (item) {
                                    is GalleryItem.Local -> viewModel.exportRecording(item.entity, RecordingExportKind.FOLDER)
                                    is GalleryItem.Remote -> viewModel.exportServerRecording(item, RecordingExportKind.FOLDER)
                                }
                            },
                            // Raw copies only exist for local clips; server rows already live on the server as H.264.
                            onExportRawFolder = if (item is GalleryItem.Local) {
                                { viewModel.exportRecording(item.entity, RecordingExportKind.RAW_FOLDER) }
                            } else null
                        )
                    }
                }
                // Pull-to-refresh re-fetches the server list, so the gesture is only offered where there is
                // something to fetch. The material3 box has no enable flag, so the container itself is chosen
                // and both branches hand the same [rows] to the same hoisted [listState].
                if (state.serverModeEnabled) {
                    PullToRefreshBox(
                        isRefreshing = state.isServerLoading,
                        onRefresh = { viewModel.refreshServerRecordings() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        GalleryList(listState = listState, content = rows)
                    }
                } else {
                    GalleryList(listState = listState, content = rows)
                }
            }

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

    activePlayback?.let { target ->
        VideoPlayerModal(
            playback = target,
            // Both writes share one frame: the bar comes back exactly when the overlay leaves composition,
            // never while its exit spring is still running.
            onDismiss = {
                activePlayback = null
                onFullscreenPlayback(false)
            }
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
