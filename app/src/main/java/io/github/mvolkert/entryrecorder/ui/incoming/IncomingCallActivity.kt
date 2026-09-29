package io.github.mvolkert.entryrecorder.ui.incoming

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.sip.SipSessionState
import io.github.mvolkert.entryrecorder.ui.components.LiveStreamPlayer

class IncomingCallActivity : ComponentActivity() {

    private val app by lazy { application as EntryRecorderApp }
    private val repository by lazy { app.repository }
    private val sipManager by lazy { app.sipCallManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setupLockscreenFlags()

        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1L)
        val eventTypeName = intent.getStringExtra(EXTRA_EVENT_TYPE) ?: EventType.RING.name
        val eventType = try { EventType.valueOf(eventTypeName) } catch (_: Exception) { EventType.RING }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black,
                    surface = Color(0xFF1E1E1E),
                    primary = Color(0xFF4CAF50),
                    error = Color(0xFFF44336)
                )
            ) {
                var device by remember { mutableStateOf<DeviceEntity?>(null) }
                val sipState by sipManager.sessionState.collectAsStateWithLifecycle()

                LaunchedEffect(deviceId) {
                    if (deviceId != -1L) {
                        device = repository.getDeviceById(deviceId)
                    }
                }

                IncomingCallContent(
                    device = device,
                    eventType = eventType,
                    sipState = sipState,
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
    device: DeviceEntity?,
    eventType: EventType,
    sipState: SipSessionState,
    onAcceptCall: () -> Unit,
    onDeclineCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Fullscreen Live Video Stream
        if (device != null) {
            LiveStreamPlayer(
                device = device,
                modifier = Modifier.fillMaxSize(),
                useController = false
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // 2. Top Header Overlay
        IncomingCallHeader(
            eventType = eventType,
            deviceName = device?.name,
            onDismiss = onDismiss
        )

        // 3. Bottom Call Controls Overlay
        IncomingCallControls(
            sipState = sipState,
            onAcceptCall = onAcceptCall,
            onDeclineCall = onDeclineCall,
            onToggleMute = onToggleMute,
            onToggleSpeaker = onToggleSpeaker
        )
    }
}
