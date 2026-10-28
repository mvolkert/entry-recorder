package io.github.mvolkert.entryrecorder.ui.components

import timber.log.Timber
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.ui.theme.VideoScrim
import io.github.mvolkert.entryrecorder.ui.theme.onScrimColor
import java.net.ConnectException

@OptIn(UnstableApi::class)
@Composable
fun RtspVideoPlayer(
    rtspUrl: String,
    modifier: Modifier = Modifier,
    useController: Boolean = false,
    autoPlay: Boolean = true
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }

    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        ExoPlayer.Builder(context, renderersFactory).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> isLoading = true
                        Player.STATE_READY -> {
                            isLoading = false
                            errorMessage = null
                        }
                        Player.STATE_ENDED -> isLoading = false
                        Player.STATE_IDLE -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    isLoading = false
                    val cause = error.cause
                    errorMessage = when {
                        cause is ConnectException ->
                            resources.getString(R.string.player_error_timeout)
                        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                            resources.getString(R.string.player_error_rtsp_not_found)
                        else -> resources.getString(
                            R.string.player_error_rtsp,
                            error.localizedMessage ?: error.errorCodeName
                        )
                    }
                }
            })
        }
    }

    LaunchedEffect(rtspUrl, autoPlay, retryCount) {
        try {
            isLoading = true
            errorMessage = null
            
            val mediaItem = MediaItem.fromUri(rtspUrl)
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setDebugLoggingEnabled(true)
                .setTimeoutMs(15000)
                .createMediaSource(mediaItem)

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = autoPlay
        } catch (e: Exception) {
            isLoading = false
            errorMessage = resources.getString(R.string.player_error_setup, e.localizedMessage ?: "")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                exoPlayer.stop()
                exoPlayer.release()
            } catch (e: Exception) {
                // A double-release on an already-released player is benign, but said out loud
                // (debug-only) so a real MediaCodec leak on this device is still traceable.
                Timber.tag("RtspVideoPlayer").d(e, "Player release failed (usually already released)")
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VideoScrim),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    this.useController = useController
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isLoading) {
            LoadingSpinner(color = onScrimColor)
        }

        errorMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(Spacing.xl)
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    Button(
                        onClick = { retryCount++ },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.player_retry_connection), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}
