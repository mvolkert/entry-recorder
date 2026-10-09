package io.github.mvolkert.entryrecorder.ui.recordings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.ui.components.StatusChip
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeColor
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeLabel
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeOnColor
import io.github.mvolkert.entryrecorder.ui.theme.onScrimColor

/**
 * The newest gallery clip shown once, big: the Playful-level hero card for the media-browse screen.
 * Filled `primaryContainer` with the expressive extraLargeIncreased shape and a large masked media
 * tile; tapping the media opens playback. The full action menu stays reachable from the card, so the
 * hero replacing the item's list row never hides export/delete behind a play-only surface.
 */
@Composable
internal fun RecordingHeroCard(
    item: GalleryItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onToggleProtect: () -> Unit,
    onShare: () -> Unit,
    onExportGallery: () -> Unit,
    onExportFolder: () -> Unit,
    onExportRawFolder: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val entity = (item as? GalleryItem.Local)?.entity
    val sizeStr = Formatter.formatFileSize(context, item.sizeBytes)

    Card(
        onClick = onPlay,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLargeIncreased,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        HeroMedia(item = item)

        Column(modifier = Modifier.padding(Spacing.xl)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item.eventTypes.forEach { type ->
                    StatusChip(
                        label = eventTypeLabel(type),
                        containerColor = eventTypeColor(type),
                        contentColor = eventTypeOnColor(type),
                    )
                }
                if (item is GalleryItem.Remote) {
                    StatusChip(
                        label = stringResource(R.string.recordings_origin_server),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(modifier = Modifier.weight(1f))
                RecordingActionsMenu(
                    isProtected = entity?.isProtected ?: (item as? GalleryItem.Remote)?.dto?.isProtected ?: false,
                    onExportFolder = onExportFolder,
                    onExportRawFolder = onExportRawFolder,
                    onShare = onShare,
                    onExportGallery = onExportGallery,
                    onToggleProtect = onToggleProtect,
                    onDelete = onDelete,
                )
            }

            Text(
                text = item.deviceName,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
            )
            Text(
                text = galleryDateStr(item.timestamp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.recordings_duration_size, item.durationSeconds, sizeStr),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The hero's media tile: 16:9, extraLarge corner mask, event-icon fallback like the list thumbnails. */
@Composable
private fun HeroMedia(item: GalleryItem) {
    val fallbackIcon = when (item.eventType) {
        EventType.RING -> Icons.Default.Call
        EventType.NOISE -> Icons.AutoMirrored.Filled.VolumeUp
        else -> Icons.Default.Videocam
    }
    val model: Any? = galleryThumbnailModel(item)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .padding(Spacing.md)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        var loadFailed by remember(model) { mutableStateOf(false) }
        if (model != null && !loadFailed) {
            AsyncImage(
                model = model,
                contentDescription = stringResource(R.string.recordings_cd_thumbnail),
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                onError = { loadFailed = true },
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Icon(
            imageVector = Icons.Default.PlayCircle,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = onScrimColor.copy(alpha = 0.85f),
        )
    }
}
