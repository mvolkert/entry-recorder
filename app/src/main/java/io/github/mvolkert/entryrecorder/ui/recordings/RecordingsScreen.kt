package io.github.mvolkert.entryrecorder.ui.recordings

import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.mvolkert.entryrecorder.ui.components.CelebrationCheck
import io.github.mvolkert.entryrecorder.ui.components.ExpressiveIconBadge
import io.github.mvolkert.entryrecorder.ui.components.LoadingSpot
import io.github.mvolkert.entryrecorder.ui.components.PlaybackTarget
import io.github.mvolkert.entryrecorder.ui.components.VideoPlayerModal
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeColor
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeOnColor
import io.github.mvolkert.entryrecorder.util.ExportHelper

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

    // Export runs in the ViewModel; its results arrive as one-shot events. The share sheet is launched
    // here because ExportHelper.shareFile needs an Activity context (it adds no NEW_TASK flag).
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is RecordingsUiEvent.Message -> Toast.makeText(
                    context,
                    event.text,
                    if (event.short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()

                is RecordingsUiEvent.Share -> {
                    val recording = event.recording
                    val file = event.files.firstOrNull()
                    when {
                        recording != null && file != null -> ExportHelper.shareFile(context, file, recording)
                        event.deviceName != null && file != null ->
                            ExportHelper.shareFile(context, file, event.deviceName, event.eventTypeLabel ?: "")
                        else -> ExportHelper.shareFiles(context, event.files)
                    }
                }

                RecordingsUiEvent.SelectionCleared -> selectionMode = false

                RecordingsUiEvent.ExportCompleted -> showSavedCheck = true
            }
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
                    onSelectAll = { viewModel.selectAllVisible() },
                    onExportSelected = { kind -> viewModel.exportSelected(kind) },
                    onBulkDelete = { showBulkDeleteConfirm = true },
                    onExitSelection = {
                        selectionMode = false
                        viewModel.clearSelection()
                    },
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
        PullToRefreshBox(
            isRefreshing = state.isServerLoading,
            onRefresh = { viewModel.refreshServerRecordings() },
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
                        ExpressiveIconBadge(
                            icon = Icons.Default.VideoLibrary,
                            contentDescription = null,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            text = stringResource(R.string.recordings_empty),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(R.string.recordings_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Every clip is one row of the same list: no hero above the newest item, so the gallery
                // reads as a uniform archive and each row keeps the same tap, long-press and menu targets.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
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
            }

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

    exportProgress?.let { progress ->
        ExportProgressDialog(progressPercent = progress.percent, bodyRes = progress.bodyRes)
    }

    batchProgress?.let { (done, total) ->
        BatchProgressDialog(done = done, total = total)
    }

    serverDownload?.let { pct ->
        DownloadProgressDialog(progressPercent = pct)
    }
}
