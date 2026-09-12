package io.github.mvolkert.entryrecorder.ui.incoming

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.sip.CallUiState
import io.github.mvolkert.entryrecorder.ui.components.RtspVideoPlayer
import kotlinx.coroutines.launch

class IncomingCallActivity : ComponentActivity() {

    private val app by lazy { application as EntryRecorderApp }
    private val repository by lazy { app.repository }
    private val sipManager by lazy { app.sipCallManager }
    private val recorder by lazy { app.recorder }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupLockscreenFlags()

        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1L)
        val eventTypeName = intent.getStringExtra(EXTRA_EVENT_TYPE) ?: EventType.RING.name
        val caller = intent.getStringExtra(EXTRA_CALLER) ?: "Doorbell"
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
                val sipState by sipManager.sessionState.collectAsState()
                val scope = rememberCoroutineScope()

                LaunchedEffect(deviceId) {
                    if (deviceId != -1L) {
                        device = repository.getDeviceById(deviceId)
                    }
                }

                IncomingCallContent(
                    device = device,
                    eventType = eventType,
                    caller = caller,
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
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
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
    caller: String,
    sipState: io.github.mvolkert.entryrecorder.sip.SipSessionState,
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
            RtspVideoPlayer(
                rtspUrl = device.rtspStreamUrl,
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
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.Black.copy(alpha = 0.65f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (eventType == EventType.RING) "🔔 Doorbell Ringing" else "👁 Motion Detected",
                        color = if (eventType == EventType.RING) Color(0xFFFFD54F) else Color(0xFF81D4FA),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = device?.name ?: "Intercom",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }

                // Close / Dismiss button
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = Color.White
                    )
                }
            }
        }

        // 3. Bottom Call Controls Overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            when (sipState.state) {
                CallUiState.CONNECTED -> {
                    // In-Call Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mute button
                        IconButton(
                            onClick = onToggleMute,
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    if (sipState.isMicMuted) Color.DarkGray else Color.White.copy(alpha = 0.2f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = if (sipState.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Mute Microphone",
                                tint = if (sipState.isMicMuted) Color.Red else Color.White
                            )
                        }

                        // Hangup Button
                        FloatingActionButton(
                            onClick = onDeclineCall,
                            containerColor = Color(0xFFE53935),
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(68.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "Hang Up",
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // Speaker toggle button
                        IconButton(
                            onClick = onToggleSpeaker,
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    if (sipState.isSpeakerOn) Color(0xFF1E88E5) else Color.White.copy(alpha = 0.2f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = if (sipState.isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                                contentDescription = "Speaker",
                                tint = Color.White
                            )
                        }
                    }
                }
                else -> {
                    // Incoming / Motion Alert Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Decline / Dismiss
                        FloatingActionButton(
                            onClick = onDeclineCall,
                            containerColor = Color(0xFFE53935),
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "Decline",
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        // Accept SIP Call
                        FloatingActionButton(
                            onClick = onAcceptCall,
                            containerColor = Color(0xFF43A047),
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Accept Call (Gegensprechen)",
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
