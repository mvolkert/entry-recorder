package io.github.mvolkert.entryrecorder.ui.incoming

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.data.network.MjpegStreamReader
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.sip.CallUiState
import io.github.mvolkert.entryrecorder.sip.SipCallManager
import io.github.mvolkert.entryrecorder.sip.SipCallTiming
import io.github.mvolkert.entryrecorder.sip.SipSessionState
import io.github.mvolkert.entryrecorder.ui.components.LiveStreamPlayer
import io.github.mvolkert.entryrecorder.ui.components.LoadingSpot
import io.github.mvolkert.entryrecorder.ui.adaptive.WindowInfo
import io.github.mvolkert.entryrecorder.ui.adaptive.rememberWindowInfo
import io.github.mvolkert.entryrecorder.ui.theme.AppTheme
import io.github.mvolkert.entryrecorder.ui.theme.ThemeMode
import io.github.mvolkert.entryrecorder.ui.theme.VideoScrim
import io.github.mvolkert.entryrecorder.ui.theme.onScrimColor
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration.Companion.milliseconds

@AndroidEntryPoint
class IncomingCallActivity : ComponentActivity() {

    @Inject lateinit var repository: IntercomRepository
    @Inject lateinit var sipManager: SipCallManager

    // The feed on this screen is the same stream the Live cards use, so it goes through the shared
    // clients instead of a second set of sockets with their own timeouts.
    @Inject lateinit var mjpegReader: MjpegStreamReader
    @Inject lateinit var snapshotClient: HttpSnapshotClient

    /**
     * What this screen is currently showing. The manifest declares `launchMode="singleTask"`, so a second
     * event intent is delivered to the live instance through [onNewIntent] instead of creating a new one —
     * the extras therefore have to be re-read there, otherwise a screen first opened for motion and later
     * reused for a doorbell press would keep showing the old device and event.
     */
    private var screen by mutableStateOf(ScreenSpec())

    private data class ScreenSpec(
        val deviceId: Long = -1L,
        val eventType: EventType = EventType.RING,
        val caller: String? = null
    )

    private fun screenFrom(intent: Intent) = ScreenSpec(
        deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1L),
        eventType = runCatching {
            EventType.valueOf(intent.getStringExtra(EXTRA_EVENT_TYPE) ?: EventType.RING.name)
        }.getOrDefault(EventType.RING),
        caller = intent.getStringExtra(EXTRA_CALLER)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setupLockscreenFlags()
        screen = screenFrom(intent)

        setContent {
            // Locked to Dark: a live video feed sits behind translucent overlays, so the call UI
            // always needs the light-on-dark contrast independent of the user's app-wide theme
            // choice. Accent roles still flow from settings so the accept FAB carries the palette.
            val settings by repository.settingsFlow
                .collectAsStateWithLifecycle(initialValue = null)
            AppTheme(
                themeMode = ThemeMode.Dark.ordinal,
                primaryIndex = settings?.themePrimaryIndex ?: 0,
                secondaryIndex = settings?.themeSecondaryIndex ?: 0,
                tertiaryIndex = settings?.themeTertiaryIndex ?: 0,
                useDynamicColor = settings?.themeUseDynamicColor ?: false,
            ) {
                var device by remember { mutableStateOf<Device?>(null) }
                val sipState by sipManager.sessionState.collectAsStateWithLifecycle()
                var sawConnected by remember { mutableStateOf(false) }

                LaunchedEffect(screen.deviceId) {
                    device = if (screen.deviceId != -1L) repository.getDeviceById(screen.deviceId) else null
                }

                // Back gesture on this Activity is a Decline: SIP is torn down and the window
                // finishes only when the user commits the gesture; a cancelled swipe leaves the
                // call untouched. The Manifest flag enables the system-level preview animation.
                PredictiveBackHandler(enabled = true) { progress: Flow<BackEventCompat> ->
                    try {
                        progress.collect { /* fraction updates: nothing to preview locally */ }
                        sipManager.terminateCall()
                        finish()
                    } catch (cancelled: CancellationException) {
                        // Gesture aborted before commit: keep the call running.
                    }
                }

                // Close the full-screen call once the call behind it is over. This screen is opened either for
                // a ring or for a motion/noise preview: a ring closes when the call ends or fails (answered or
                // missed), a preview stays open because it never carries a call of its own.
                LaunchedEffect(sipState.state, screen.eventType) {
                    when (sipState.state) {
                        CallUiState.CONNECTED -> sawConnected = true
                        CallUiState.ENDED, CallUiState.ERROR -> if (screen.eventType == EventType.RING || sawConnected) {
                            sawConnected = false
                            delay(SipCallTiming.TERMINAL_CALL_DISMISS_MS.milliseconds)
                            finish()
                        }
                        CallUiState.IDLE, CallUiState.RINGING_INCOMING -> Unit
                    }
                }

                IncomingCallContent(
                    device = device,
                    eventType = screen.eventType,
                    caller = screen.caller,
                    sipState = sipState,
                    mjpegReader = mjpegReader,
                    snapshotClient = snapshotClient,
                    onAcceptCall = {
                        sipManager.acceptCall()
                    },
                    onDeclineCall = {
                        sipManager.terminateCall()
                        finish()
                    },
                    onToggleMute = {
                        sipManager.setMicrophoneMuted(!sipState.isMicMuted)
                    },
                    onToggleSpeaker = {
                        sipManager.routeAudioToSpeaker(!sipState.isSpeakerOn)
                    },
                    onDismiss = {
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        screen = screenFrom(intent)
    }

    private fun setupLockscreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_EVENT_TYPE = "extra_event_type"
        const val EXTRA_CALLER = "extra_caller"
    }
}

@Composable
fun IncomingCallContent(
    device: Device?,
    eventType: EventType,
    caller: String?,
    sipState: SipSessionState,
    mjpegReader: MjpegStreamReader,
    snapshotClient: HttpSnapshotClient,
    onAcceptCall: () -> Unit,
    onDeclineCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDismiss: () -> Unit
) {
    val windowInfo = rememberWindowInfo()
    val scrim = MaterialTheme.colorScheme.scrim

    // Video pane: the stream fills whatever slot the layout gives it; the loading placeholder sits
    // behind the same Box so both branches swap only the incoming container.
    val video: @Composable (Modifier) -> Unit = { mod ->
        if (device != null) {
            LiveStreamPlayer(
                device = device,
                mjpegReader = mjpegReader,
                snapshotClient = snapshotClient,
                modifier = mod,
                useController = false,
            )
        } else {
            Box(modifier = mod, contentAlignment = Alignment.Center) {
                // Device row not loaded yet: the expressive three-shape loader, tinted for the scrim.
                LoadingSpot(color = onScrimColor)
            }
        }
    }
    val header: @Composable () -> Unit = {
        IncomingCallHeader(
            eventType = eventType,
            deviceName = device?.name,
            caller = caller,
            onDismiss = onDismiss,
        )
    }
    val controls: @Composable BoxScope.() -> Unit = {
        IncomingCallControls(
            sipState = sipState,
            onAcceptCall = onAcceptCall,
            onDeclineCall = onDeclineCall,
            onToggleMute = onToggleMute,
            onToggleSpeaker = onToggleSpeaker,
        )
    }

    if (windowInfo == WindowInfo.Expanded) {
        // Tablet landscape: video on the wider left pane, header + controls in a scrim-backed
        // right pane so they keep the same light-on-dark contrast as the Compact overlay.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(VideoScrim)
        ) {
            video(Modifier.weight(3f).fillMaxHeight())
            Box(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight()
                    .background(scrim.copy(alpha = 0.7f)),
            ) {
                header()
                controls()
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VideoScrim)
        ) {
            video(Modifier.fillMaxSize())
            header()
            controls()
        }
    }
}
