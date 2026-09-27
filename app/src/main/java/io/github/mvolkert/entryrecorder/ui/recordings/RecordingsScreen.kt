package io.github.mvolkert.entryrecorder.ui.recordings

import android.text.format.Formatter
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.ui.components.VideoPlayerModal
import io.github.mvolkert.entryrecorder.util.ExportHelper
import io.github.mvolkert.entryrecorder.video.ExportTranscoder
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    viewModel: RecordingsViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transcodeOnExport by viewModel.transcodeOnExport.collectAsState()
    val exportFolderUri by viewModel.exportFolderUri.collectAsState()
    var activePlaybackRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var recordingToDelete by remember { mutableStateOf<RecordingEntity?>(null) }
    var exportProgress by remember { mutableStateOf<Int?>(null) }

    // Multi-select delete mode
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds by viewModel.selectedIds.collectAsState()
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var batchTotal by remember { mutableStateOf<Int?>(null) }
    var batchDone by remember { mutableStateOf(0) }

    // Exports an MJPEG MKV by first transcoding to H.264 (so it plays in other apps) when the
    // setting is enabled; otherwise shares/saves the raw file. Transcoding runs only here (on
    // explicit user action), never during capture. This path is Share-only: it uses the system
    // share sheet and does NOT persist anything to the user's export folder.
    fun runExport(recording: RecordingEntity, share: Boolean) {
        val src = File(recording.filePath)
        val isMjpegMkv = src.extension.equals("mkv", ignoreCase = true)
        if (!(transcodeOnExport && isMjpegMkv)) {
            if (share) ExportHelper.shareFile(context, src, recording)
            else ExportHelper.saveFileToGallery(context, src, recording)
            return
        }
        exportProgress = 0
        scope.launch {
            try {
                val out = ExportTranscoder.transcodeToH264(context, recording) { done, total ->
                    val pct = if (total > 0) (done * 100 / total) else 0
                    scope.launch { exportProgress = pct }
                }
                exportProgress = null
                if (share) ExportHelper.shareFile(context, out, recording)
                else ExportHelper.saveFileToGallery(context, out, recording)
            } catch (e: Exception) {
                exportProgress = null
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Persists an export to the user's SAF folder as ONE file per recording: the re-encoded H.264
    // MKV when the transcode setting produced one, otherwise the original MJPEG MKV. The lossless
    // original always stays in-app (and Settings can mirror originals to this folder at
    // finalization), so the folder isn't cluttered with near-duplicate, hard-to-play files.
    fun exportToFolder(recording: RecordingEntity) {
        if (exportFolderUri.isBlank()) {
            Toast.makeText(context, "Set an export folder in Settings first.", Toast.LENGTH_LONG).show()
            return
        }
        val src = File(recording.filePath)
        if (!src.exists()) {
            Toast.makeText(context, "Recording file not found", Toast.LENGTH_SHORT).show()
            return
        }
        val isMjpegMkv = src.extension.equals("mkv", ignoreCase = true)
        val willTranscode = transcodeOnExport && isMjpegMkv

        scope.launch {
            try {
                if (willTranscode) exportProgress = 0
                val h264 = if (willTranscode) {
                    ExportTranscoder.transcodeToH264(context, recording) { done, total ->
                        val pct = if (total > 0) (done * 100 / total) else 0
                        scope.launch { exportProgress = pct }
                    }
                } else null
                exportProgress = null

                // One file per recording: the H.264 re-encode when transcoding produced one,
                // otherwise the original MKV.
                val out = h264 ?: src
                val treeUri = Uri.parse(exportFolderUri)
                val saved = ExportHelper.saveFileToSafFolder(context, treeUri, out, out.name)

                val label = ExportHelper.safFolderDisplayName(treeUri)
                Toast.makeText(
                    context,
                    if (saved) "Exported to $label" else "Export to folder failed",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                exportProgress = null
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Multi-select export. Applies the same lazy transcode-on-export policy as the single-item
    // path but across all selected recordings, with a k/n progress dialog. Share collects the
    // (transcoded-when-enabled) files and hands them to the system sheet in one action; Gallery
    // and Folder write each file and report a final saved count. kind: "SHARE" | "GALLERY" | "FOLDER".
    fun runBatchExport(kind: String) {
        val targets = state.recordings.filter { it.id in selectedIds }
        if (targets.isEmpty()) {
            Toast.makeText(context, "Nothing selected", Toast.LENGTH_SHORT).show()
            return
        }
        if (kind == "FOLDER" && exportFolderUri.isBlank()) {
            Toast.makeText(context, "Set an export folder in Settings first.", Toast.LENGTH_LONG).show()
            return
        }
        batchTotal = targets.size
        batchDone = 0
        scope.launch {
            val toShare = ArrayList<File>()
            var saved = 0
            try {
                for (rec in targets) {
                    val src = File(rec.filePath)
                    val isMjpegMkv = src.extension.equals("mkv", ignoreCase = true)
                    val willTranscode = transcodeOnExport && isMjpegMkv && src.exists()
                    val h264 = if (willTranscode) {
                        try { ExportTranscoder.transcodeToH264(context, rec) } catch (e: Exception) { null }
                    } else null

                    when (kind) {
                        "SHARE" -> toShare.add(h264 ?: src)
                        "GALLERY" -> {
                            val out = h264 ?: src
                            if (ExportHelper.saveFileToGallery(context, out, rec, showToast = false)) saved++
                        }
                        "FOLDER" -> {
                            // One file per recording (H.264 when produced, else original) — same as the single-item path.
                            val treeUri = Uri.parse(exportFolderUri)
                            val out = h264 ?: src
                            if (ExportHelper.saveFileToSafFolder(context, treeUri, out, out.name)) saved++
                        }
                    }
                    batchDone += 1
                }

                when (kind) {
                    "SHARE" -> if (toShare.isNotEmpty()) ExportHelper.shareFiles(context, toShare)
                    "GALLERY" -> Toast.makeText(context, "Saved $saved of ${targets.size} to Gallery", Toast.LENGTH_LONG).show()
                    "FOLDER" -> Toast.makeText(context, "Exported $saved of ${targets.size} to folder", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                batchTotal = null
                batchDone = 0
                selectionMode = false
                viewModel.clearSelection()
            }
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectionMode) "${selectedIds.size} selected" else "Recordings Archive",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (selectionMode) {
                        IconButton(onClick = { viewModel.selectAllVisible() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                        Box {
                            IconButton(
                                onClick = { showExportMenu = true },
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = "Export selected")
                            }
                            DropdownMenu(
                                expanded = showExportMenu,
                                onDismissRequest = { showExportMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Share…") },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                    onClick = {
                                        showExportMenu = false
                                        runBatchExport("SHARE")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Save to Gallery") },
                                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                    onClick = {
                                        showExportMenu = false
                                        runBatchExport("GALLERY")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export to Folder") },
                                    leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                                    onClick = {
                                        showExportMenu = false
                                        runBatchExport("FOLDER")
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
                                contentDescription = "Delete selected",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(onClick = {
                            selectionMode = false
                            viewModel.clearSelection()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    } else {
                        IconButton(onClick = { selectionMode = true }) {
                            Icon(Icons.Default.Checklist, contentDescription = "Select multiple")
                        }
                    }
                }
                Text(
                    text = "Total storage: ${Formatter.formatFileSize(context, state.totalStorageBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Search field
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search by device name or note...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
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
                            label = { Text("All Events") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.selectedEventType == EventType.RING,
                            onClick = { viewModel.selectEventTypeFilter(EventType.RING) },
                            label = { Text("🔔 Doorbell Rings") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.selectedEventType == EventType.MOTION,
                            onClick = { viewModel.selectEventTypeFilter(EventType.MOTION) },
                            label = { Text("👁 Motion") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.selectedEventType == EventType.NOISE,
                            onClick = { viewModel.selectEventTypeFilter(EventType.NOISE) },
                            label = { Text("🔊 Noise") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.selectedEventType == EventType.MANUAL,
                            onClick = { viewModel.selectEventTypeFilter(EventType.MANUAL) },
                            label = { Text("✋ Manual") }
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
                                label = { Text("📷 All devices") }
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
                        text = "No recordings found.",
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
                            onShare = { runExport(recording, share = true) },
                            onExportGallery = { runExport(recording, share = false) },
                            onExportFolder = { exportToFolder(recording) }
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
            title = { Text("Delete Recording?") },
            text = { Text("Are you sure you want to delete this recording from ${rec.deviceName}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRecording(rec)
                        recordingToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { recordingToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Bulk Delete Confirmation Dialog
    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("Delete ${selectedIds.size} recording(s)?") },
            text = { Text("This permanently deletes the selected recordings and their files. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected()
                    showBulkDeleteConfirm = false
                    selectionMode = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Export (transcode) progress
    exportProgress?.let { pct ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Preparing export…") },
            text = {
                Column {
                    Text("Converting to H.264 for universal playback…  $pct%")
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {}
        )
    }

    // Batch export progress (multi-select): shows "k of n" across the selected recordings while
    // each item is transcoded (when the setting is on) and written/shared.
    batchTotal?.let { total ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting…") },
            text = {
                Column {
                    Text("Processing $batchDone of $total recording(s)…")
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
                        contentDescription = "Thumbnail",
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
                    contentDescription = "Play",
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
                    text = "${recording.durationSeconds}s • $sizeStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Actions Menu
            var menuExpanded by remember { mutableStateOf(false) }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Export to folder") },
                        leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onExportFolder()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Save to Gallery") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onExportGallery()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (recording.isProtected) "Unprotect" else "Protect against Auto-Cleanup") },
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
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
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
