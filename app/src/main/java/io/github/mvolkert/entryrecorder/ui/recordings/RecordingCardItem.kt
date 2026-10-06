package io.github.mvolkert.entryrecorder.ui.recordings

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.ui.theme.appMotionScheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * One merged gallery row. Both origins expose the same action menu; a [GalleryItem.Local] additionally
 * participates in multi-select, while a [GalleryItem.Remote] (a server recording) has no checkbox and its
 * menu actions round-trip to the server API instead of touching local storage.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecordingCardItem(
    item: GalleryItem,
    selectionMode: Boolean,
    selected: Boolean,
    onSelectToggle: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onToggleProtect: () -> Unit,
    onShare: () -> Unit,
    onExportGallery: () -> Unit,
    onExportFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", locale).format(Date(item.timestamp))
    val sizeStr = Formatter.formatFileSize(context, item.sizeBytes)
    val isRemote = item is GalleryItem.Remote
    val entity = (item as? GalleryItem.Local)?.entity
    val selectedContainer = MaterialTheme.colorScheme.secondaryContainer
    val restingContainer = MaterialTheme.colorScheme.surface
    val containerColor by animateColorAsState(
        targetValue = if (selected && !isRemote) selectedContainer else restingContainer,
        animationSpec = MaterialTheme.appMotionScheme.defaultEffectsSpec(),
        label = "recordingCardContainer",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = { if (selectionMode && !isRemote) onSelectToggle() else onPlay() }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode && !isRemote) {
                Checkbox(checked = selected, onCheckedChange = { onSelectToggle() })
                Spacer(modifier = Modifier.width(4.dp))
            }
            RecordingThumbnail(item = item)

            Spacer(modifier = Modifier.width(12.dp))

            RecordingDetails(item = item, dateStr = dateStr, sizeStr = sizeStr)

            // Local and server rows share the same menu; the origin only changes what each handler does
            // (a server action calls the API, a local action reads/writes the on-device file).
            RecordingActionsMenu(
                isProtected = entity?.isProtected ?: (item as? GalleryItem.Remote)?.dto?.isProtected ?: false,
                onExportFolder = onExportFolder,
                onShare = onShare,
                onExportGallery = onExportGallery,
                onToggleProtect = onToggleProtect,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun RecordingThumbnail(item: GalleryItem) {
    val fallbackIcon = when (item.eventType) {
        EventType.RING -> Icons.Default.Call
        EventType.NOISE -> Icons.AutoMirrored.Filled.VolumeUp
        else -> Icons.Default.Videocam
    }
    // Coil accepts a File (local thumbnail) or a URL string (server thumbnail); null or a failed load
    // falls back to the event icon, so an unreachable server shows an icon rather than a black box.
    val model: Any? = when (item) {
        is GalleryItem.Local -> item.entity.thumbnailPath?.let { if (File(it).exists()) File(it) else null }
        is GalleryItem.Remote -> item.thumbnailAbsoluteUrl
    }

    Box(
        modifier = Modifier
            .size(90.dp, 68.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray),
        contentAlignment = Alignment.Center
    ) {
        // A failed thumbnail load (e.g. an unreachable server) falls back to the event icon rather
        // than leaving an empty dark box.
        var loadFailed by remember(model) { mutableStateOf(false) }
        if (model != null && !loadFailed) {
            AsyncImage(
                model = model,
                contentDescription = stringResource(R.string.recordings_cd_thumbnail),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { loadFailed = true }
            )
        } else {
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
}

@Composable
private fun RowScope.RecordingDetails(item: GalleryItem, dateStr: String, sizeStr: String) {
    Column(modifier = Modifier.weight(1f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // One badge per trigger the clip covers: a doorbell pressed during a motion recording is the same
            // file, so it shows as RING MOTION rather than hiding one of the two events.
            item.eventTypes.forEach { type ->
                val badgeColor = when (type) {
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
                        text = type.name,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = item.deviceName,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (item is GalleryItem.Remote) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                ) {
                    Text(
                        text = stringResource(R.string.recordings_origin_server),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Text(
            text = dateStr,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = stringResource(
                R.string.recordings_duration_size,
                item.durationSeconds,
                sizeStr
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecordingActionsMenu(
    isProtected: Boolean,
    onExportFolder: () -> Unit,
    onShare: () -> Unit,
    onExportGallery: () -> Unit,
    onToggleProtect: () -> Unit,
    onDelete: () -> Unit
) {
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
                            if (isProtected) R.string.recordings_unprotect
                            else R.string.recordings_protect
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        if (isProtected) Icons.Default.LockOpen else Icons.Default.Lock,
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
