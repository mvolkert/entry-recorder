package io.github.mvolkert.entryrecorder.ui.recordings

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.ui.components.VideoPlayerModal
import io.github.mvolkert.entryrecorder.util.ExportHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    modifier: Modifier = Modifier,
    viewModel: RecordingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val exportProgress by viewModel.exportProgress.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    var activePlaybackRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var recordingToDelete by remember { mutableStateOf<RecordingEntity?>(null) }

    // Multi-select delete mode
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds by viewModel.selectedIds.collectAsState()
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }

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
                    if (recording != null && file != null) {
                        ExportHelper.shareFile(context, file, recording)
                    } else {
                        ExportHelper.shareFiles(context, event.files)
                    }
                }

                RecordingsUiEvent.SelectionCleared -> selectionMode = false
            }
        }
    }

    Scaffold(
        // Top inset is applied manually on the custom Column topBar below (this is not a
        // TopAppBar, so it does not self-consume the status bar); bottom system inset comes from
        // the host NavigationBar in MainActivity. Nested Scaffold stays inset-free to avoid
        // double-counting.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectionMode)
                            stringResource(R.string.recordings_selected_count, selectedIds.size)
                        else stringResource(R.string.recordings_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (selectionMode) {
                        IconButton(onClick = { viewModel.selectAllVisible() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.recordings_cd_select_all))
                        }
                        Box {
                            IconButton(
                                onClick = { showExportMenu = true },
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.recordings_cd_export_selected))
                            }
                            DropdownMenu(
                                expanded = showExportMenu,
                                onDismissRequest = { showExportMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.recordings_share_batch)) },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                    onClick = {
                                        showExportMenu = false
                                        viewModel.exportSelected(RecordingExportKind.SHARE)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.recordings_save_gallery_menu)) },
                                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                    onClick = {
                                        showExportMenu = false
                                        viewModel.exportSelected(RecordingExportKind.GALLERY)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.recordings_export_folder_batch)) },
                                    leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                                    onClick = {
                                        showExportMenu = false
                                        viewModel.exportSelected(RecordingExportKind.FOLDER)
                                    }
                                )
                            }
                        }
                        IconButton(
                            onClick = { showBulkDeleteConfirm = true },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.recordings_cd_delete_selected),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(onClick = {
                            selectionMode = false
                            viewModel.clearSelection()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.recordings_cd_exit_selection))
                        }
                    } else {
                        IconButton(onClick = { selectionMode = true }) {
                            Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.recordings_cd_select_multiple))
                        }
                    }
                }
                Text(
                    text = stringResource(
                        R.string.recordings_total_storage,
                        Formatter.formatFileSize(context, state.totalStorageBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Search field
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.recordings_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.recordings_cd_search)) },
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.recordings_cd_clear))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Event Type Filter Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedEventType == null,
                            onClick = { viewModel.selectEventTypeFilter(null) },
                            label = { Text(stringResource(R.string.recordings_filter_all_events)) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.selectedEventType == EventType.RING,
                            onClick = { viewModel.selectEventTypeFilter(EventType.RING) },
                            label = { Text(stringResource(R.string.recordings_filter_ring)) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.selectedEventType == EventType.MOTION,
                            onClick = { viewModel.selectEventTypeFilter(EventType.MOTION) },
                            label = { Text(stringResource(R.string.recordings_filter_motion)) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.selectedEventType == EventType.NOISE,
                            onClick = { viewModel.selectEventTypeFilter(EventType.NOISE) },
                            label = { Text(stringResource(R.string.recordings_filter_noise)) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.selectedEventType == EventType.MANUAL,
                            onClick = { viewModel.selectEventTypeFilter(EventType.MANUAL) },
                            label = { Text(stringResource(R.string.recordings_filter_manual)) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Device Filter Chips
                if (state.devices.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = state.selectedDeviceId == null,
                                onClick = { viewModel.selectDeviceFilter(null) },
                                label = { Text(stringResource(R.string.recordings_filter_all_devices)) }
                            )
                        }
                        items(state.devices, key = { "dev_${it.id}" }) { device ->
                            FilterChip(
                                selected = state.selectedDeviceId == device.id,
                                onClick = {
                                    viewModel.selectDeviceFilter(if (state.selectedDeviceId == device.id) null else device.id)
                                },
                                label = { Text(device.name) },
                                leadingIcon = if (state.selectedDeviceId == device.id) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                        }
                    }
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
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (state.recordings.isEmpty()) {
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
                    items(state.recordings, key = { it.id }) { recording ->
                        RecordingCardItem(
                            recording = recording,
                            selectionMode = selectionMode,
                            selected = recording.id in selectedIds,
                            onSelectToggle = { viewModel.toggleSelection(recording.id) },
                            onPlay = { activePlaybackRecording = recording },
                            onDelete = { recordingToDelete = recording },
                            onToggleProtect = { viewModel.toggleProtection(recording) },
                            onShare = { viewModel.exportRecording(recording, RecordingExportKind.SHARE) },
                            onExportGallery = { viewModel.exportRecording(recording, RecordingExportKind.GALLERY) },
                            onExportFolder = { viewModel.exportRecording(recording, RecordingExportKind.FOLDER) }
                        )
                    }
                }
            }
        }
    }

    // Video Player Dialog
    activePlaybackRecording?.let { rec ->
        VideoPlayerModal(
            recording = rec,
            onDismiss = { activePlaybackRecording = null }
        )
    }

    // Delete Confirmation Dialog
    recordingToDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { recordingToDelete = null },
            title = { Text(stringResource(R.string.recordings_delete_title)) },
            text = { Text(stringResource(R.string.recordings_delete_body, rec.deviceName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRecording(rec)
                        recordingToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { recordingToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Bulk Delete Confirmation Dialog
    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text(pluralStringResource(R.plurals.recordings_bulk_delete_title, selectedIds.size, selectedIds.size)) },
            text = { Text(stringResource(R.string.recordings_bulk_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected()
                    showBulkDeleteConfirm = false
                    selectionMode = false
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Export (transcode) progress
    exportProgress?.let { pct ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.recordings_export_progress_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.recordings_export_progress_body, pct))
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {}
        )
    }

    // Batch export progress (multi-select): shows "k of n" across the selected recordings while
    // each item is transcoded (when the setting is on) and written/shared.
    batchProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.recordings_batch_progress_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.recordings_batch_progress_body, done, total))
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {}
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingCardItem(
    recording: RecordingEntity,
    selectionMode: Boolean,
    selected: Boolean,
    onSelectToggle: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onToggleProtect: () -> Unit,
    onShare: () -> Unit,
    onExportGallery: () -> Unit,
    onExportFolder: () -> Unit
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", locale).format(Date(recording.timestamp))
    val sizeStr = Formatter.formatFileSize(context, recording.fileSizeBytes)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = { if (selectionMode) onSelectToggle() else onPlay() }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onSelectToggle() })
                Spacer(modifier = Modifier.width(4.dp))
            }
            // Thumbnail / Event Icon
            Box(
                modifier = Modifier
                    .size(90.dp, 68.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                if (recording.thumbnailPath != null && File(recording.thumbnailPath).exists()) {
                    AsyncImage(
                        model = File(recording.thumbnailPath),
                        contentDescription = stringResource(R.string.recordings_cd_thumbnail),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    val fallbackIcon = when (recording.eventType) {
                        EventType.RING -> Icons.Default.Call
                        EventType.NOISE -> Icons.AutoMirrored.Filled.VolumeUp
                        else -> Icons.Default.Videocam
                    }
                    Icon(
                        imageVector = fallbackIcon,
                        contentDescription = null,
                        tint = Color.White
                    )
                }

                // Play icon overlay
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = stringResource(R.string.recordings_cd_play),
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Event Badge
                    val badgeColor = when (recording.eventType) {
                        EventType.RING -> Color(0xFFFF9800)
                        EventType.MOTION -> Color(0xFF0288D1)
                        EventType.NOISE -> Color(0xFF8E24AA)
                        EventType.MANUAL -> Color(0xFF43A047)
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = badgeColor
                    ) {
                        Text(
                            text = recording.eventType.name,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = recording.deviceName,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = stringResource(
                        R.string.recordings_duration_size,
                        recording.durationSeconds,
                        sizeStr
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Actions Menu
            var menuExpanded by remember { mutableStateOf(false) }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.recordings_cd_options))
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recordings_export_folder_menu)) },
                        leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onExportFolder()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recordings_share_menu)) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recordings_save_gallery_menu)) },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onExportGallery()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (recording.isProtected) R.string.recordings_unprotect
                                    else R.string.recordings_protect
                                )
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (recording.isProtected) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onToggleProtect()
                        }
                    )
                    HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
