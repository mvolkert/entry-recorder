package io.github.mvolkert.entryrecorder.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.mvolkert.entryrecorder.R
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
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
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f), shape = RoundedCornerShape(50))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.player_cd_close),
                    tint = Color.White
                )
            }
        }
    }
}

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
