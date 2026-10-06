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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
import io.github.mvolkert.entryrecorder.ui.components.PlaybackTarget
import io.github.mvolkert.entryrecorder.ui.components.VideoPlayerModal
import io.github.mvolkert.entryrecorder.util.ExportHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    modifier: Modifier = Modifier,
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

    // Server recordings are a lazy one-shot fetch, refreshed when the screen is entered (a no-op outside
    // PYTHON_SERVER mode). Local Room data drives the list immediately and never blocks on the network.
    LaunchedEffect(Unit) {
        viewModel.refreshServerRecordings()
    }

    // Multi-select delete mode
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
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
                    selectedCount = selectedIds.size,
                    onSelectAll = { viewModel.selectAllVisible() },
                    onExportSelected = { kind -> viewModel.exportSelected(kind) },
                    onBulkDelete = { showBulkDeleteConfirm = true },
                    onExitSelection = {
                        selectionMode = false
                        viewModel.clearSelection()
                    },
                    onEnterSelection = { selectionMode = true }
                )

                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    RecordingsStorageLine(totalStorageBytes = state.totalStorageBytes)
                    Spacer(modifier = Modifier.height(8.dp))
                    RecordingsFilterBar(
                        searchQuery = state.searchQuery,
                        onSearchQueryChange = { viewModel.setSearchQuery(it) },
                        selectedEventType = state.selectedEventType,
                        onEventTypeSelect = { viewModel.selectEventTypeFilter(it) },
                        devices = state.devices,
                        selectedDeviceId = state.selectedDeviceId,
                        onDeviceSelect = { viewModel.selectDeviceFilter(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.recordings_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.items, key = { it.stableKey }) { item ->
                        RecordingCardItem(
                            modifier = Modifier.animateItem(),
                            item = item,
                            selectionMode = selectionMode,
                            selected = item is GalleryItem.Local && item.entity.id in selectedIds,
                            onSelectToggle = {
                                (item as? GalleryItem.Local)?.let { viewModel.toggleSelection(it.entity.id) }
                            },
                            onPlay = {
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
                            }
                        )
                    }
                }
            }
        }
    }

    activePlayback?.let { target ->
        VideoPlayerModal(
            playback = target,
            onDismiss = { activePlayback = null }
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
            selectedCount = selectedIds.size,
            onDismiss = { showBulkDeleteConfirm = false },
            onConfirm = {
                viewModel.deleteSelected()
                showBulkDeleteConfirm = false
                selectionMode = false
            }
        )
    }

    exportProgress?.let { pct ->
        ExportProgressDialog(progressPercent = pct)
    }

    batchProgress?.let { (done, total) ->
        BatchProgressDialog(done = done, total = total)
    }

    serverDownload?.let { pct ->
        DownloadProgressDialog(progressPercent = pct)
    }
}
