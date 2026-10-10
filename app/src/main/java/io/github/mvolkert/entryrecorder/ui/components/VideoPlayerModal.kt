package io.github.mvolkert.entryrecorder.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.ui.theme.VideoScrim
import io.github.mvolkert.entryrecorder.ui.theme.onScrimColor
import io.github.mvolkert.entryrecorder.ui.theme.rememberExpressiveMotionEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * What a playback dialog should show. A local app recording is a file on this device (its MJPEG MKV is
 * played by the dedicated JPEG player, anything else by ExoPlayer); a server recording is an absolute
 * HTTP URL (already carrying the API key) that ExoPlayer streams directly.
 */
sealed interface PlaybackTarget {
    data class LocalFile(val filePath: String) : PlaybackTarget
    data class RemoteUrl(val url: String) : PlaybackTarget
}

/**
 * Full-screen playback host. Rendered as an overlay inside the caller's own layout rather than a
 * separate window ([androidx.compose.ui.window.Dialog]), so it inherits the activity's edge-to-edge
 * insets and theme instead of re-establishing them, and its system-back handling stays local to the
 * composable that owns the playback state. It enters with the motion scheme's spring scale-and-fade,
 * or a plain crossfade when system animations are off.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerModal(
    playback: PlaybackTarget,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local app recordings are crash-safe MKV files holding JPEG frames on a V_MJPEG track, which
    // ExoPlayer cannot decode; play those with the dedicated JPEG frame player. Anything else (a local
    // non-MKV file, or any server row, which is H.264 fMP4/mp4) keeps using ExoPlayer.
    val isJpegMkv = playback is PlaybackTarget.LocalFile &&
            playback.filePath.substringAfterLast('.', "").equals("mkv", ignoreCase = true)

    // The overlay content animates in and out through the motion scheme; the exit is not observable
    // from here (AnimatedVisibility exposes no completion hook), so dismissal is deferred by
    // EXIT_FALLBACK_MS to let the spring play out before the caller drops the composable.
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val motionEnabled = rememberExpressiveMotionEnabled()
    val fadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val scaleSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(Unit) { visible = true }
    val requestDismiss: () -> Unit = {
        if (visible) {
            visible = false
            scope.launch {
                delay(EXIT_FALLBACK_MS)
                onDismiss()
            }
        }
    }
    // The overlay is in-window now, so the system back gesture has to be routed to the dismiss
    // animation by hand — the Dialog window used to do this for free.
    BackHandler(enabled = visible) { requestDismiss() }

    AnimatedVisibility(
        visible = visible,
        // The spring scale entrance is the expressive form; with system animations off the overlay
        // only crossfades.
        enter = if (motionEnabled) {
            fadeIn(animationSpec = fadeSpec) + scaleIn(animationSpec = scaleSpec, initialScale = 0.92f)
        } else {
            fadeIn(animationSpec = fadeSpec)
        },
        exit = if (motionEnabled) {
            fadeOut(animationSpec = fadeSpec) + scaleOut(animationSpec = scaleSpec, targetScale = 0.92f)
        } else {
            fadeOut(animationSpec = fadeSpec)
        },
        modifier = modifier
            .fillMaxSize()
            // Swallow touches that would otherwise fall through to the list behind the scrim.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        label = "videoPlayer",
    ) {
        // Single dim layer behind the media, with the player filling it and the close control on top.
        Box(modifier = Modifier.fillMaxSize().background(VideoScrim)) {
            val localPath = (playback as? PlaybackTarget.LocalFile)?.filePath
            if (isJpegMkv && localPath != null) {
                JpegFramePlayer(
                    filePath = localPath,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val uri = when (playback) {
                    is PlaybackTarget.RemoteUrl -> playback.url
                    is PlaybackTarget.LocalFile -> File(playback.filePath).toURI().toString()
                }
                ExoPlayerView(
                    uri = uri,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Close button top right, kept clear of the status bar now that it is not in its own window, and
            // of a landscape cutout on top of that - stacked calls, since no insets union is needed here.
            IconButton(
                onClick = requestDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(Spacing.lg)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f), shape = CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.player_cd_close),
                    tint = onScrimColor
                )
            }
        }
    }
}

/** Covers the fast-spring fade/scale exit before the overlay is dropped by its caller. */
private const val EXIT_FALLBACK_MS = 320L

@OptIn(UnstableApi::class)
@Composable
private fun ExoPlayerView(
    uri: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    // A stopped screen must not keep playing: the audio and the decoder would run behind another app.
    // What the user had it set to is recorded on the way out, so a clip they paused themselves comes back
    // paused instead of rolling again.
    var playBeforeStop by remember(exoPlayer) { mutableStateOf(true) }
    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    playBeforeStop = exoPlayer.playWhenReady
                    exoPlayer.playWhenReady = false
                }

                Lifecycle.Event.ON_START -> exoPlayer.playWhenReady = playBeforeStop
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = modifier
    )
}
