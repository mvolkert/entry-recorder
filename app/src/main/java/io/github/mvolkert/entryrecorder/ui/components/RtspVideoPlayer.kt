package io.github.mvolkert.entryrecorder.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
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
                        cause is ConnectException -> "Connection Timed Out. Check if the intercom is on the same Wi-Fi."
                        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "RTSP Error: 404 Not Found. Check your stream path."
                        else -> "RTSP Error: ${error.localizedMessage ?: error.errorCodeName}"
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
            errorMessage = "Setup Error: ${e.localizedMessage}"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                exoPlayer.stop()
                exoPlayer.release()
            } catch (_: Exception) {
                // Ignore errors during release
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
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
            CircularProgressIndicator(color = Color.White)
        }

        errorMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { retryCount++ },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry Connection", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}
