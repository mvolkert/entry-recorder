package io.github.mvolkert.entryrecorder.ui.recordings

import android.widget.Toast
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.ui.components.ExpressiveIconBadge
import io.github.mvolkert.entryrecorder.ui.components.LoadingSpot
import io.github.mvolkert.entryrecorder.ui.components.PlaybackTarget
import io.github.mvolkert.entryrecorder.ui.components.VideoPlayerModal
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.util.ExportHelper

/** Shared-transition key for one gallery clip's media tile, matched by the hero card and the player. */
private fun heroMediaKey(stableKey: String) = "hero-media:$stableKey"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun RecordingsScreen(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    sharedTransitionScope: SharedTransitionScope? = null,
    onFullscreenPlayback: (Boolean) -> Unit = {},
    viewModel: RecordingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportProgress by viewModel.exportProgress.collectAsStateWithLifecycle()
    val batchProgress by viewModel.batchProgress.collectAsStateWithLifecycle()
    val serverDownload by viewModel.serverDownload.collectAsStateWithLifecycle()
    var activePlayback by remember { mutableStateOf<PlaybackTarget?>(null) }
    // Which clip is playing, as its shared-transition key: the hero tile steps out of the list for it
    // and the player overlay picks the same key up so the two ends morph into each other.
    var playingHeroKey by remember { mutableStateOf<String?>(null) }
    var recordingToDelete by remember { mutableStateOf<RecordingEntity?>(null) }
    var serverItemToDelete by remember { mutableStateOf<GalleryItem.Remote?>(null) }

    // The player fills the window instead of sitting above the navigation bar, and leaves composition
    // without a dismiss callback (tab swiped away, process restore), so the chrome is restored there too.
    LaunchedEffect(activePlayback) { onFullscreenPlayback(activePlayback != null) }
    DisposableEffect(Unit) { onDispose { onFullscreenPlayback(false) } }

    // All pager pages stay composed, so a player left open while swiping to another tab would keep the
    // window fullscreen and the navigation bar hidden behind a page nobody is looking at. Playback is
    // this screen's own state, so leaving the page ends it.
    LaunchedEffect(active) {
        if (!active) {
            activePlayback = null
            playingHeroKey = null
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
                // The newest clip leaves the list and becomes the hero card above it, except while
                // multi-selecting (every row must stay selectable there). Keyed distinctly so the
                // hero and its dropped row can never collide in the LazyColumn.
                val heroItem = if (!selectionMode) state.items.first() else null
                val listItems = if (heroItem != null) state.items.drop(1) else state.items
                val heroTransitionKey = heroItem?.let { heroMediaKey(it.stableKey) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    if (heroItem != null) {
                        item(key = "hero:${heroItem.stableKey}") {
                            RecordingHeroCard(
                                modifier = Modifier.animateItem(),
                                item = heroItem,
                                heroKey = heroTransitionKey,
                                sharedTransitionScope = sharedTransitionScope,
                                isPlaying = playingHeroKey == heroTransitionKey,
                                onPlay = {
                                    playingHeroKey = heroTransitionKey
                                    activePlayback = when (heroItem) {
                                        is GalleryItem.Local -> PlaybackTarget.LocalFile(heroItem.entity.filePath)
                                        is GalleryItem.Remote -> PlaybackTarget.RemoteUrl(heroItem.videoAbsoluteUrl)
                                    }
                                },
                                onDelete = {
                                    when (heroItem) {
                                        is GalleryItem.Local -> recordingToDelete = heroItem.entity
                                        is GalleryItem.Remote -> serverItemToDelete = heroItem
                                    }
                                },
                                onToggleProtect = {
                                    when (heroItem) {
                                        is GalleryItem.Local -> viewModel.toggleProtection(heroItem.entity)
                                        is GalleryItem.Remote -> viewModel.toggleServerProtection(heroItem)
                                    }
                                },
                                onShare = {
                                    when (heroItem) {
                                        is GalleryItem.Local -> viewModel.exportRecording(heroItem.entity, RecordingExportKind.SHARE)
                                        is GalleryItem.Remote -> viewModel.exportServerRecording(heroItem, RecordingExportKind.SHARE)
                                    }
                                },
                                onExportGallery = {
                                    when (heroItem) {
                                        is GalleryItem.Local -> viewModel.exportRecording(heroItem.entity, RecordingExportKind.GALLERY)
                                        is GalleryItem.Remote -> viewModel.exportServerRecording(heroItem, RecordingExportKind.GALLERY)
                                    }
                                },
                                onExportFolder = {
                                    when (heroItem) {
                                        is GalleryItem.Local -> viewModel.exportRecording(heroItem.entity, RecordingExportKind.FOLDER)
                                        is GalleryItem.Remote -> viewModel.exportServerRecording(heroItem, RecordingExportKind.FOLDER)
                                    }
                                },
                                // Raw copies only exist for local clips; server rows already live on the server as H.264.
                                onExportRawFolder = if (heroItem is GalleryItem.Local) {
                                    { viewModel.exportRecording(heroItem.entity, RecordingExportKind.RAW_FOLDER) }
                                } else null
                            )
                        }
                    }
                    items(listItems, key = { it.stableKey }) { item ->
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
                                playingHeroKey = heroMediaKey(item.stableKey)
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
        }
    }

    activePlayback?.let { target ->
        VideoPlayerModal(
            playback = target,
            heroKey = playingHeroKey,
            sharedTransitionScope = sharedTransitionScope,
            onDismiss = {
                activePlayback = null
                playingHeroKey = null
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
