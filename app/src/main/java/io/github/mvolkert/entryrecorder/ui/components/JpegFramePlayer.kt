package io.github.mvolkert.entryrecorder.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.theme.onScrimColor
import io.github.mvolkert.entryrecorder.video.MjpegMkvReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
    val lifecycleOwner = LocalLifecycleOwner.current

    var refs by remember(filePath) { mutableStateOf<List<MjpegMkvReader.FrameRef>>(emptyList()) }
    var index by remember(filePath) { mutableIntStateOf(0) }
    var playing by remember(filePath) { mutableStateOf(true) }
    var resumeAfterScrub by remember(filePath) { mutableStateOf(false) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    // A frame that could not be read at all, as opposed to one still being decoded: the MKV index can be
    // perfectly readable while the JPEG bytes behind it are not.
    var frameFailed by remember(filePath) { mutableStateOf(false) }
    // Bumped by Retry so the decode effect re-runs for the position it is already on; clearing
    // [frameFailed] alone would not restart the effect and the error would never lift.
    var decodeAttempt by remember(filePath) { mutableIntStateOf(0) }
    // The first decoded frame fades in over the placeholder; alpha is an appearance, so it gets the motion
    // scheme's effects spec. Later frames must swap hard — crossfading video into the next video frame
    // smears the motion — and this value simply stays at 1 once the first bitmap has landed.
    val frameAlpha = remember(filePath) { Animatable(0f) }

    LaunchedEffect(filePath) {
        refs = withContext(Dispatchers.IO) { reader.readFrameIndex() }
        index = 0
    }

    // Decode the current frame whenever the position changes. Gated on the lifecycle like the schedule
    // below, so a stopped screen stops opening the file and decoding frames no one can see.
    LaunchedEffect(index, refs, decodeAttempt, lifecycleOwner) {
        if (refs.isEmpty()) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val ref = refs.getOrNull(index) ?: return@repeatOnLifecycle
            val bytes = runCatching { withContext(Dispatchers.IO) { reader.readFrame(ref) } }.getOrNull()
            val bmp = bytes?.let {
                withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
            if (bmp == null) frameFailed = true else {
                frame = bmp
                frameFailed = false
            }
        }
    }

    // Timing loop: advance frames on their recorded inter-frame deltas, then loop. Each tick is a
    // wall-clock deadline rather than another delay(dwell) chained onto the work: the decode an index
    // change triggers runs in its own effect (on IO, while this one is suspended in delay), so the old
    // per-frame `delay(dwell)` + decode put a *variable* decode latency on top of every dwell, which is
    // what made the stepping uneven. Against a deadline the interval stays the dwell and the decode only
    // offsets every frame equally.
    LaunchedEffect(playing, refs, lifecycleOwner) {
        if (!playing || refs.size < 2) return@LaunchedEffect
        // Stopped (app backgrounded, screen locked) has nobody to show the next frame to, so the schedule
        // suspends with it. The deadline is re-anchored on every START, so returning to the clip costs a
        // single dwell and never the burst of frames "missed" while the app was away.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var nextDue = nowMs()
            while (isActive && playing) {
                val cur = refs.getOrNull(index) ?: break
                val next = refs.getOrNull(index + 1)
                val dwell = if (next != null) (next.timestampMs - cur.timestampMs).coerceAtLeast(20L) else 200L
                nextDue += dwell
                val wait = nextDue - nowMs()
                if (wait <= 0L) {
                    // Behind schedule (a scrub, a slow decode, a loop wrap) is re-anchored, never repaid as a
                    // burst of frames catching up.
                    nextDue = nowMs()
                } else {
                    delay(wait.milliseconds)
                }
                index = if (index + 1 >= refs.size) {
                    nextDue = nowMs()
                    0
                } else {
                    index + 1
                }
            }
        }
    }

    // The effect's coroutine is not a composable scope, so the motion spec is resolved here.
    val firstFrameSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(frame != null) {
        if (frame != null && frameAlpha.value == 0f) {
            frameAlpha.animateTo(1f, firstFrameSpec)
        }
    }

    val totalMs = remember(refs) {
        if (refs.size >= 2) refs.last().timestampMs - refs.first().timestampMs else 0L
    }
    val currentMs = refs.getOrNull(index)?.let { it.timestampMs - refs.first().timestampMs } ?: 0L
    // Resolved outside the semantics lambda, which is not a composable scope.
    val seekLabel = stringResource(R.string.player_cd_seek)

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
        } else if (frameFailed) {
            // A frame that cannot be read never becomes readable by waiting, and an endless spinner tells
            // the user the file is still arriving. Say it is damaged and offer the one useful action.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_error_decode),
                    color = onScrimColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        frameFailed = false
                        decodeAttempt++
                    },
                    // The scrim is the surface here, so the label takes the scrim's own text colour rather
                    // than the theme primary, which is only guaranteed against a light surface.
                    colors = ButtonDefaults.textButtonColors(contentColor = onScrimColor)
                ) {
                    Text(stringResource(R.string.player_retry))
                }
            }
        } else if (img != null) {
            Image(
                bitmap = img,
                contentDescription = stringResource(R.string.player_cd_frame),
                modifier = Modifier.fillMaxSize().alpha(frameAlpha.value),
                contentScale = ContentScale.Fit
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        // Transport controls: timestamps row above an edge-to-edge timeline. The strip reaches the window
        // edge but keeps its contents clear of the system bar, because the player is handed the whole
        // window and the navigation bar would otherwise cover the timeline. background first, insets
        // second: a background fills the region including padding added later in the chain. The cutout is
        // stacked rather than unioned with the bars, so a landscape punch-hole cannot eat the timeline.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .windowInsetsPadding(WindowInsets.displayCutout)
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
                    text = stringResource(
                        R.string.player_time_clock,
                        formatPlayerClock(currentMs), formatPlayerClock(totalMs), index + 1, refs.size
                    ),
                    color = onScrimColor,
                    // labelSmall already carries the medium weight a scrim caption needs.
                    style = MaterialTheme.typography.labelSmall,
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
                    // Announcing a bare percentage would not say what the handle is a position *in*.
                    .semantics { contentDescription = seekLabel }
                    // One thumb footprint of clearance so the 44dp-tall M3 handle never sits flush to the screen edge.
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * Monotonic millisecond clock for the frame schedule. `System.nanoTime` rather than
 * `SystemClock.elapsedRealtime` so the timing stays reachable from a plain JVM test.
 */
private fun nowMs(): Long = System.nanoTime() / 1_000_000L

/**
 * A player position as `m:ss`, growing to `h:mm:ss` past an hour. Plain ASCII digits and a clamped
 * negative keep a damaged timestamp from ever printing "-1 s" or a locale's alternate numerals, and the
 * whole gallery reads the same clock.
 */
internal fun formatPlayerClock(ms: Long): String {
    val totalSeconds = if (ms < 0L) 0L else ms / 1000L
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    val minutes = totalSeconds / 60 % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:$seconds"
    } else {
        "$minutes:$seconds"
    }
}
