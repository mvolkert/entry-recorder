package io.github.mvolkert.entryrecorder.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.theme.onScrimColor
import io.github.mvolkert.entryrecorder.video.MjpegMkvReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Lightweight in-app player for the `V_MJPEG` MKV recordings produced locally by the app.
 *
 * Those files store each frame as a standalone JPEG, which ExoPlayer/Media3 cannot decode as a
 * video track. Rather than re-encoding at capture time (a continuous CPU/battery cost), this player
 * decodes and displays the stored JPEG frames directly on demand, so playback is cheap and instant.
 * Random access via [MjpegMkvReader]'s frame index enables scrubbing without loading every frame.
 */
@Composable
fun JpegFramePlayer(
    filePath: String,
    modifier: Modifier = Modifier
) {
    val file = remember(filePath) { File(filePath) }
    val reader = remember(filePath) { MjpegMkvReader(file) }

    var refs by remember(filePath) { mutableStateOf<List<MjpegMkvReader.FrameRef>>(emptyList()) }
    var index by remember(filePath) { mutableIntStateOf(0) }
    var playing by remember(filePath) { mutableStateOf(true) }
    var resumeAfterScrub by remember(filePath) { mutableStateOf(false) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(filePath) {
        refs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            reader.readFrameIndex()
        }
        index = 0
    }

    // Decode the current frame whenever the position changes.
    LaunchedEffect(index, refs) {
        val ref = refs.getOrNull(index) ?: return@LaunchedEffect
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { reader.readFrame(ref) }.getOrNull()
        } ?: return@LaunchedEffect
        val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
        frame = bmp
    }

    // Timing loop: advance frames using their recorded inter-frame deltas, then loop.
    LaunchedEffect(playing, refs) {
        if (!playing || refs.size < 2) return@LaunchedEffect
        while (isActive && playing) {
            val cur = refs.getOrNull(index) ?: break
            val next = refs.getOrNull(index + 1)
            val dwell = if (next != null) (next.timestampMs - cur.timestampMs).coerceAtLeast(20L) else 200L
            delay(dwell.milliseconds)
            index = if (index + 1 >= refs.size) 0 else index + 1
        }
    }

    val totalMs = remember(refs) {
        if (refs.size >= 2) refs.last().timestampMs - refs.first().timestampMs else 0L
    }
    val currentMs = refs.getOrNull(index)?.let { it.timestampMs - refs.first().timestampMs } ?: 0L

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f))) {
        val img = frame
        if (refs.isEmpty()) {
            Text(
                text = stringResource(
                    if (file.exists()) R.string.player_no_frames else R.string.player_file_not_found
                ),
                color = onScrimColor,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (img != null) {
            Image(
                bitmap = img,
                contentDescription = stringResource(R.string.player_cd_frame),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        // Transport controls: timestamps row above an edge-to-edge timeline, flush to the bottom.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                IconButton(onClick = { if (refs.size >= 2) playing = !playing }) {
                    MorphingIcon(
                        imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.player_cd_pause else R.string.player_cd_play),
                        tint = onScrimColor
                    )
                }
                Text(
                    text = pluralStringResource(
                        R.plurals.player_time_frames, refs.size,
                        currentMs / 1000, totalMs / 1000, index + 1, refs.size
                    ),
                    color = onScrimColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                )
            }
            val accent = MaterialTheme.colorScheme.primary
            Slider(
                value = if (refs.size <= 1) 0f else index.toFloat(),
                onValueChange = {
                    // A drag always pauses the loop so the frame under the thumb stays put, and remembers
                    // that it was running — a clip the user stopped first stays stopped.
                    if (playing) {
                        playing = false
                        resumeAfterScrub = true
                    }
                    index = it.toInt()
                },
                onValueChangeFinished = {
                    if (resumeAfterScrub) {
                        playing = true
                        resumeAfterScrub = false
                    }
                },
                valueRange = 0f..(refs.size - 1).coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = accent.copy(alpha = 0.32f),
                    disabledThumbColor = accent.copy(alpha = 0.38f),
                    disabledActiveTrackColor = accent.copy(alpha = 0.38f),
                    disabledInactiveTrackColor = accent.copy(alpha = 0.12f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    // One thumb footprint of clearance so the 44dp-tall M3 handle never sits flush to the screen edge.
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }
    }
}
