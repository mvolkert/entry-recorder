package io.github.mvolkert.entryrecorder.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.ui.theme.VideoScrim
import io.github.mvolkert.entryrecorder.ui.theme.onScrimColor
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

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerModal(
    playback: PlaybackTarget,
    onDismiss: () -> Unit
) {
    // Local app recordings are crash-safe MKV files holding JPEG frames on a V_MJPEG track, which
    // ExoPlayer cannot decode; play those with the dedicated JPEG frame player. Anything else (a local
    // non-MKV file, or any server row, which is H.264 fMP4/mp4) keeps using ExoPlayer.
    val isJpegMkv = playback is PlaybackTarget.LocalFile &&
            playback.filePath.substringAfterLast('.', "").equals("mkv", ignoreCase = true)

    // The dialog content animates in and out through the motion scheme; the Dialog itself can only
    // be dismissed once the exit has played, so dismissal is deferred by EXIT_FALLBACK_MS rather
    // than handed straight to onDismiss (AnimatedVisibility exposes no completion hook here).
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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

    Dialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = fadeSpec) + scaleIn(animationSpec = scaleSpec, initialScale = 0.92f),
            exit = fadeOut(animationSpec = fadeSpec) + scaleOut(animationSpec = scaleSpec, targetScale = 0.92f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VideoScrim)
            ) {
                if (isJpegMkv && playback is PlaybackTarget.LocalFile) {
                    JpegFramePlayer(
                        filePath = playback.filePath,
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

                // Close button top right
                IconButton(
                    onClick = requestDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
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
}

/** Covers the fast-spring fade/scale exit before the Dialog window is torn down. */
private const val EXIT_FALLBACK_MS = 320L

@OptIn(UnstableApi::class)
@Composable
private fun ExoPlayerView(
    uri: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
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
