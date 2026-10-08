package io.github.mvolkert.entryrecorder.ui.components

import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.data.network.MjpegStreamReader
import io.github.mvolkert.entryrecorder.ui.theme.VideoScrim
import io.github.mvolkert.entryrecorder.ui.theme.onScrimColor
import io.github.mvolkert.entryrecorder.ui.theme.streamProtocolLabel
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.ConnectException
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
@Composable
fun LiveStreamPlayer(
    device: DeviceEntity,
    modifier: Modifier = Modifier,
    useController: Boolean = false,
    autoPlay: Boolean = true,
    /**
     * When non-null, the feed is pulled from this server MJPEG URL (`/api/live/{serverDeviceId}/mjpeg`)
     * instead of the phone dialing the device directly (Phase S: server owns the camera poll in a relay
     * deployment). On stream failure the player falls back to the direct-to-device path below.
     */
    serverLiveUrl: String? = null,
    serverApiKey: String? = null,
    /**
     * Receives the intrinsic width/height ratio of the rendered feed as soon as it is known (first
     * decoded bitmap or RTSP video size). Lets the host container adapt its border to the video
     * instead of letterboxing a fixed-height box. Debounced to changes > 0.01.
     */
    onAspectRatioChanged: ((Float) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var activeProtocol by remember(device.id, device.streamProtocol) {
        mutableStateOf(
            if (device.streamProtocol == StreamProtocol.AUTO) StreamProtocol.RTSP else device.streamProtocol
        )
    }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Latched when the server feed fails, so this card drops to the direct path instead of looping on the
    // server. Reset when the target URL changes (device switch) or on a manual retry.
    var serverFailed by remember(serverLiveUrl) { mutableStateOf(false) }
    val renderServer = serverLiveUrl != null && !serverFailed
    val resources = LocalResources.current

    // Debounce the aspect report against decoder jitter and per-frame size noise; reset on protocol
    // fallback so RTSP -> MJPEG -> snapshot can re-report a different ratio.
    val lastReportedRatio = remember(device.id, activeProtocol) { mutableFloatStateOf(0f) }
    fun reportRatio(ratio: Float) {
        if (ratio > 0f && abs(ratio - lastReportedRatio.floatValue) > 0.01f) {
            lastReportedRatio.floatValue = ratio
            onAspectRatioChanged?.invoke(ratio)
        }
    }

    // RTSP ExoPlayer instance
    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        ExoPlayer.Builder(context, renderersFactory).build()
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            try {
                exoPlayer.stop()
                exoPlayer.release()
            } catch (_: Exception) {}
        }
    }

    // Effect for handling streaming lifecycle based on active protocol
    LaunchedEffect(device, activeProtocol, retryCount, autoPlay, serverLiveUrl, serverApiKey, serverFailed) {
        // Inactive tab page (the pager keeps neighbours composed off-screen): stop the player and skip
        // all stream setup, keeping the radio idle; the last decoded frame stays on screen. Flipping
        // autoPlay back to true re-runs this effect and reconnects.
        if (!autoPlay) {
            exoPlayer.stop()
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        errorMessage = null

        // Server-relayed live view (Phase S): one MJPEG multipart stream pulled from the server, which owns
        // the camera poll. Any failure latches [serverFailed] so this effect re-runs into the direct path.
        val serverUrl = serverLiveUrl
        if (serverUrl != null && !serverFailed) {
            exoPlayer.stop()
            val mjpegReader = MjpegStreamReader()
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    mjpegReader.streamBitmapsFromUrl(serverUrl, device.name, serverApiKey)
                        .collect { bmp ->
                            latestBitmap = bmp
                            reportRatio(bmp.width.toFloat() / bmp.height.toFloat())
                            isLoading = false
                            errorMessage = null
                        }
                    // Stream ended cleanly (server closed it): treat as a failure to fall back.
                    serverFailed = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("LiveStreamPlayer", "Server live feed failed, falling back to direct: ${e.message}")
                    serverFailed = true
                }
            }
            return@LaunchedEffect
        }

        when (activeProtocol) {
            StreamProtocol.RTSP, StreamProtocol.AUTO -> {
                try {
                    val listener = object : Player.Listener {
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

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.height > 0) {
                                // PAR-corrected: anamorphic streams store a square-pixel size that
                                // differs from what is actually displayed.
                                reportRatio(
                                    videoSize.width.toFloat() / videoSize.height.toFloat() *
                                        videoSize.pixelWidthHeightRatio
                                )
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            isLoading = false
                            Log.w("LiveStreamPlayer", "RTSP Playback error: ${error.errorCodeName}")

                            // Fallback to MJPEG or Snapshot in AUTO mode
                            if (device.streamProtocol == StreamProtocol.AUTO) {
                                Log.i("LiveStreamPlayer", "RTSP failed, auto-falling back to MJPEG/Snapshot")
                                activeProtocol = StreamProtocol.MJPEG_STREAM
                            } else {
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
                        }
                    }

                    exoPlayer.addListener(listener)
                    val mediaItem = MediaItem.fromUri(device.rtspStreamUrl)
                    val mediaSource = RtspMediaSource.Factory()
                        .setForceUseRtpTcp(true)
                        .setTimeoutMs(10000)
                        .createMediaSource(mediaItem)

                    exoPlayer.setMediaSource(mediaSource)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                } catch (e: Exception) {
                    isLoading = false
                    if (device.streamProtocol == StreamProtocol.AUTO) {
                        activeProtocol = StreamProtocol.MJPEG_STREAM
                    } else {
                        errorMessage = resources.getString(R.string.player_error_rtsp_setup, e.localizedMessage ?: "")
                    }
                }
            }

            StreamProtocol.MJPEG_STREAM -> {
                // Ensure ExoPlayer is stopped when in MJPEG mode
                exoPlayer.stop()
                val mjpegReader = MjpegStreamReader()
                // Same gate as the snapshot branch: a long-lived multipart response keeps the radio and
                // the decoder busy after the screen is stopped, so cancelling on STOP ends the call and
                // a returning user reconnects.
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        mjpegReader.streamBitmaps(device).collect { bmp ->
                            latestBitmap = bmp
                            reportRatio(bmp.width.toFloat() / bmp.height.toFloat())
                            isLoading = false
                            errorMessage = null
                        }
                    } catch (e: Exception) {
                        Log.w("LiveStreamPlayer", "MJPEG stream error: ${e.message}")
                        // If MJPEG failed and was in AUTO mode, fall back to snapshot polling
                        if (device.streamProtocol == StreamProtocol.AUTO) {
                            activeProtocol = StreamProtocol.HTTP_SNAPSHOT
                        } else {
                            isLoading = false
                            errorMessage = resources.getString(R.string.player_error_mjpeg_disconnected, e.localizedMessage ?: "")
                        }
                    }
                }
            }

            StreamProtocol.HTTP_SNAPSHOT -> {
                exoPlayer.stop()
                val frameIntervalMs = 1000L / device.effectiveSnapshotFps
                // Poll only while the UI is at least STARTED: an ungated loop kept hitting the
                // snapshot endpoint after the activity stopped, draining the radio unattended.
                // repeatOnLifecycle cancels the loop on STOP and restarts it on the next START.
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // fetchSnapshotBitmap already hops to IO, so keep the loop (and the Compose state
                    // writes) on the caller dispatcher and pace against a rolling deadline: subtracting
                    // the fetch time makes the achieved rate match snapshotFps instead of fetch + interval.
                    coroutineScope {
                        var nextFrameAt = System.currentTimeMillis()
                        while (isActive) {
                            nextFrameAt += frameIntervalMs
                            val bmp = HttpSnapshotClient.fetchSnapshotBitmap(device)
                            if (bmp != null) {
                                latestBitmap = bmp
                                reportRatio(bmp.width.toFloat() / bmp.height.toFloat())
                                isLoading = false
                                errorMessage = null
                            } else if (latestBitmap == null) {
                                isLoading = false
                                errorMessage = resources.getString(R.string.player_error_snapshot)
                            }
                            val remainingMs = nextFrameAt - System.currentTimeMillis()
                            if (remainingMs > 0) delay(remainingMs.milliseconds)
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VideoScrim),
        contentAlignment = Alignment.Center
    ) {
        if (activeProtocol == StreamProtocol.RTSP && !renderServer) {
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
        } else {
            // MJPEG or Snapshot Bitmap Renderer
            latestBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.player_cd_live_feed),
                    // Fit, matching the RTSP `PlayerView` above (whose default resize mode is fit): a
                    // landscape camera frame in this portrait container would otherwise be scaled until it
                    // covered the screen, discarding most of the scene as zoom.
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Active Protocol Badge (Top Left)
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
            contentColor = onScrimColor
        ) {
            Text(
                text = if (renderServer) stringResource(R.string.live_badge_server)
                else streamProtocolLabel(activeProtocol),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        if (isLoading) {
            CircularProgressIndicator(color = onScrimColor)
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
                        onClick = { serverFailed = false; retryCount++ },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.player_retry_connection), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}
