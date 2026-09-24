package io.github.mvolkert.entryrecorder.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
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
                text = if (file.exists()) "No playable frames." else "Recording file not found.",
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (img != null) {
            Image(
                bitmap = img,
                contentDescription = "Recording frame",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        // Transport controls
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .fillMaxWidth(),
            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                IconButton(onClick = { if (refs.size >= 2) playing = !playing }) {
                    Icon(
                        imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }
                Slider(
                    value = if (refs.size <= 1) 0f else index.toFloat(),
                    onValueChange = { playing = false; index = it.toInt() },
                    valueRange = 0f..(refs.size - 1).coerceAtLeast(1).toFloat(),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${currentMs / 1000}s / ${totalMs / 1000}s  •  ${index + 1}/${refs.size}",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp)
                )
            }
        }
    }
}
