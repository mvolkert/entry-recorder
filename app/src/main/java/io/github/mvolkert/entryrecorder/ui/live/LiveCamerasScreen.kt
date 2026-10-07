package io.github.mvolkert.entryrecorder.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.ui.components.LiveStreamPlayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCamerasScreen(
    modifier: Modifier = Modifier,
    viewModel: LiveViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        // Top inset is handled by the TopAppBar below; bottom system inset is applied by the
        // host NavigationBar in MainActivity, so this nested Scaffold must not re-add system-bar
        // insets (that would double-count them).
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.live_title), fontWeight = FontWeight.Bold) }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (state.devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideocamOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.live_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.live_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(state.devices, key = { it.id }) { device ->
                        val isRecording = device.id in state.recordingDeviceIds
                        val monitorStatus = if (!device.isEnabled) MonitorStatus.DISABLED
                        else state.monitorStatuses[device.id] ?: MonitorStatus.DISABLED
                        val eventQuality = state.eventQualities[device.id]
                        // Phase S: in PYTHON_SERVER mode an app device carries a server id, so the feed can be
                        // relayed through the server (which owns the camera poll). Otherwise dial the device.
                        val settings = state.appSettings
                        val serverLiveUrl =
                            if (settings.recordingMode == RecordingMode.PYTHON_SERVER && device.serverDeviceId != null)
                                "${settings.serverBaseUrl.trimEnd('/')}/api/live/${device.serverDeviceId}/mjpeg"
                            else null
                        LiveDeviceCard(
                            modifier = Modifier.animateItem(),
                            device = device,
                            isRecording = isRecording,
                            isMonitored = device.isEnabled,
                            monitorStatus = monitorStatus,
                            eventQuality = eventQuality,
                            serverLiveUrl = serverLiveUrl,
                            serverApiKey = settings.serverApiKey.ifBlank { null },
                            onToggleRecord = {
                                if (isRecording) {
                                    viewModel.stopManualRecording(device)
                                } else {
                                    viewModel.startManualRecording(device)
                                }
                            },
                            onToggleMonitor = { viewModel.toggleMonitoring(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LiveDeviceCard(
    device: DeviceEntity,
    isRecording: Boolean,
    isMonitored: Boolean,
    monitorStatus: MonitorStatus,
    eventQuality: ConnectionQuality?,
    serverLiveUrl: String?,
    serverApiKey: String?,
    onToggleRecord: () -> Unit,
    onToggleMonitor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Learned from the player once the first frame decodes; null until then. The video box adopts it
    // so the feed sits snug inside the border instead of letterboxing a fixed-height container.
    var videoAspectRatio by remember(device.id) { mutableStateOf<Float?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // All card status is grouped on the right: the monitoring pill, then the REC badge.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonitorStatusPill(monitorStatus)
                    if (isRecording) {
                        Spacer(modifier = Modifier.width(6.dp))
                        // Compact badge: total height stays below the device-name line height, so the
                        // header row (and the card/video layout) never grows or shrinks when it appears.
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Red
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .background(Color.White, shape = RoundedCornerShape(50))
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    stringResource(R.string.live_rec_badge),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    lineHeight = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // Live Video Player Box — height tracks the feed's aspect (clamped so a misreported or
            // portrait stream can't blow up the row); 16:9 placeholder while joining.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(videoAspectRatio?.coerceIn(1.2f, 2.6f) ?: 16f / 9f)
                    .background(Color.Black)
            ) {
                LiveStreamPlayer(
                    device = device,
                    modifier = Modifier.fillMaxSize(),
                    useController = false,
                    serverLiveUrl = serverLiveUrl,
                    serverApiKey = serverApiKey,
                    onAspectRatioChanged = { videoAspectRatio = it }
                )
            }

            // Card Bottom Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: identity + the triggers this camera is armed for.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.live_device_addr, device.ipAddress, device.rtspPort),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    TriggerIndicators(
                        device = device,
                        monitorStatus = monitorStatus,
                        eventQuality = eventQuality
                    )
                }

                // Right: the card's actions — monitor on/off, then manual record.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleMonitor) {
                        Icon(
                            imageVector = if (isMonitored) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = stringResource(
                                if (isMonitored) R.string.live_cd_stop_monitor else R.string.live_cd_monitor
                            ),
                            tint = if (isMonitored) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onToggleRecord) {
                        if (isRecording) {
                            Icon(
                                imageVector = Icons.Default.StopCircle,
                                contentDescription = stringResource(R.string.live_cd_stop_recording),
                                tint = Color.Red
                            )
                        } else {
                            // Classic record button: red dot centered in a ring
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(Color.Red, CircleShape)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Card status pill: the monitoring-state dot and its label, grouped on the header's right with the
 * REC badge so all card status reads together. DISABLED covers both an unmonitored device
 * (isEnabled = false) and one the service has not taken over yet.
 */
@Composable
private fun MonitorStatusPill(status: MonitorStatus) {
    val labelRes = when (status) {
        MonitorStatus.DISABLED -> R.string.live_status_disabled
        MonitorStatus.MONITORING -> R.string.live_status_monitoring
        MonitorStatus.MOTION -> R.string.live_status_motion
        MonitorStatus.DEGRADED -> R.string.live_status_degraded
        MonitorStatus.OFFLINE -> R.string.live_status_offline
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(monitorStatusColor(status), shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Color of the device's live health, driven by the [MonitorStatus] the monitor service publishes
 * (degraded polling fallback and an offline camera used to both look exactly like a healthy green).
 */
@Composable
private fun monitorStatusColor(status: MonitorStatus): Color = when (status) {
    MonitorStatus.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    MonitorStatus.MONITORING -> Color(0xFF4CAF50)
    MonitorStatus.MOTION -> Color(0xFF4CAF50)
    MonitorStatus.DEGRADED -> Color(0xFFFFB300)
    MonitorStatus.OFFLINE -> Color(0xFFF44336)
}

/**
 * Tiny per-trigger indicators for every recording trigger configured on [device]:
 * bell = record on doorbell ring, walking man = record on motion (native or on-device
 * analysis; turns amber while motion is currently detected), speaker = record on noise.
 *
 * The status dot is snapshot-driven, so a degraded/offline camera event stream is shown here instead:
 * the triggers it feeds (doorbell, camera-reported motion, noise) fade when [eventQuality] is not
 * healthy. In-app motion rides the snapshot path and stays judged by the dot, so an event outage does
 * not fade it. Faded also means the service has not taken this camera over yet (not monitored).
 */
@Composable
private fun TriggerIndicators(
    device: DeviceEntity,
    monitorStatus: MonitorStatus,
    eventQuality: ConnectionQuality?
) {
    val monitored = monitorStatus != MonitorStatus.DISABLED
    val normal = MaterialTheme.colorScheme.onSurfaceVariant
    val faded = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val eventsDown = eventQuality == ConnectionQuality.DEGRADED || eventQuality == ConnectionQuality.OFFLINE
    val eventTint = if (monitored && !eventsDown) normal else faded
    val baseTint = if (monitored) normal else faded
    val motionNow = monitorStatus == MonitorStatus.MOTION
    // Camera-reported motion depends on the event stream; in-app motion does not.
    val walkUsesEvents = device.recordOnMotion && !device.recordOnMotionOnDevice
    val walkTint = if (walkUsesEvents) eventTint else baseTint
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (device.isEnabled && device.recordOnRing) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = stringResource(R.string.live_cd_ring),
                modifier = Modifier.size(16.dp),
                tint = eventTint
            )
        }
        if (device.isEnabled && (device.recordOnMotion || device.recordOnMotionOnDevice)) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                contentDescription = stringResource(
                    if (motionNow) R.string.live_cd_motion_now else R.string.live_cd_motion
                ),
                modifier = Modifier.size(16.dp),
                tint = if (motionNow) Color(0xFFFFB300) else walkTint
            )
        }
        if (device.isEnabled && device.recordOnNoise) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(R.string.live_cd_noise),
                modifier = Modifier.size(16.dp),
                tint = eventTint
            )
        }
    }
}
