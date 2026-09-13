package io.github.mvolkert.entryrecorder.ui.recordings

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    var activePlaybackRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var recordingToDelete by remember { mutableStateOf<RecordingEntity?>(null) }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Recordings Archive",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
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
                            onPlay = { activePlaybackRecording = recording },
                            onDelete = { recordingToDelete = recording },
                            onToggleProtect = { viewModel.toggleProtection(recording) },
                            onShare = { ExportHelper.shareRecording(context, recording) },
                            onExportGallery = { ExportHelper.saveToPublicGallery(context, recording) }
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingCardItem(
    recording: RecordingEntity,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onToggleProtect: () -> Unit,
    onShare: () -> Unit,
    onExportGallery: () -> Unit
) {
    val context = LocalContext.current
    val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(recording.timestamp))
    val sizeStr = Formatter.formatFileSize(context, recording.fileSizeBytes)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onPlay),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                        text = { Text("Share / Export") },
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
                    HorizontalDivider()
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
