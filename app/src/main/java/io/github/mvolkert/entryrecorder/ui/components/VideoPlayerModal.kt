package io.github.mvolkert.entryrecorder.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
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
 * separate window ([androidx.compose.ui.window.Dialog]), because shared-element transitions only
 * match composables that sit in one window and one shared-transition layout: the Recordings hero tile
 * morphs into this surface when [heroKey] and [sharedTransitionScope] are given. Without them — or
 * with the system animation scale at zero — it falls back to the plain scale-and-fade entrance.
 */
@OptIn(UnstableApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun VideoPlayerModal(
    playback: PlaybackTarget,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    heroKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
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
    val heroTransition = sharedTransitionScope != null && heroKey != null && motionEnabled
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
        // A matched hero already moves the media, so only fade the scrim; the scale entrance is the
        // fallback for the unmatched / animations-off path.
        enter = if (heroTransition) fadeIn(animationSpec = fadeSpec) else fadeIn(animationSpec = fadeSpec) + scaleIn(animationSpec = scaleSpec, initialScale = 0.92f),
        exit = if (heroTransition) fadeOut(animationSpec = fadeSpec) else fadeOut(animationSpec = fadeSpec) + scaleOut(animationSpec = scaleSpec, targetScale = 0.92f),
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
        // Single dim layer behind the media. The shared node holds only the player, so the morphing
        // tile never drags the full-screen scrim along with it.
        Box(modifier = Modifier.fillMaxSize().background(VideoScrim)) {
            val surface = Modifier.fillMaxSize()
            Box(
                modifier = if (sharedTransitionScope != null && heroKey != null && motionEnabled) {
                    with(sharedTransitionScope) {
                        surface.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = heroKey),
                            animatedVisibilityScope = this@AnimatedVisibility,
                            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                            clipInOverlayDuringTransition = OverlayClip(MaterialTheme.shapes.extraLarge),
                        )
                    }
                } else {
                    surface
                },
            ) {
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

                // Close button top right, kept clear of the status bar now that it is not in its own window.
                IconButton(
                    onClick = requestDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.statusBars)
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

/** Covers the fast-spring fade/scale exit before the overlay is dropped by its caller. */
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
