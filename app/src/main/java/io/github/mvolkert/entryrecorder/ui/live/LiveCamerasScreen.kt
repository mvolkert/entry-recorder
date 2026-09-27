package io.github.mvolkert.entryrecorder.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import io.github.mvolkert.entryrecorder.ui.components.LiveStreamPlayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCamerasScreen(
    modifier: Modifier = Modifier,
    viewModel: LiveViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Intercom View", fontWeight = FontWeight.Bold) }
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
                            text = "No Intercoms Configured",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Go to the Settings tab to add your 2N IP Verso intercom device.",
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
                        LiveDeviceCard(
                            device = device,
                            isRecording = isRecording,
                            monitorStatus = monitorStatus,
                            onToggleRecord = {
                                if (isRecording) {
                                    viewModel.stopManualRecording(device)
                                } else {
                                    viewModel.startManualRecording(device)
                                }
                            }
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
    monitorStatus: MonitorStatus,
    onToggleRecord: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color(0xFF4CAF50), shape = RoundedCornerShape(50))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isRecording) {
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
                                "REC",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                lineHeight = 12.sp
                            )
                        }
                    }
                }
            }

            // Live Video Player Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .background(Color.Black)
            ) {
                LiveStreamPlayer(
                    device = device,
                    modifier = Modifier.fillMaxSize(),
                    useController = false
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
                Text(
                    text = "${device.ipAddress} (RTSP ${device.rtspPort})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonitorStatusDot(status = monitorStatus)
                    TriggerIndicators(device = device, monitorStatus = monitorStatus)
                    Spacer(modifier = Modifier.width(10.dp))
                    IconButton(onClick = onToggleRecord) {
                        if (isRecording) {
                            Icon(
                                imageVector = Icons.Default.StopCircle,
                                contentDescription = "Stop manual recording",
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
 * Live monitoring status of a device as published by IntercomMonitorService:
 * teal = events are being monitored, amber = motion currently detected,
 * grey = not monitored (device disabled or the service has not picked it up yet).
 * Always rendered at a fixed size so state changes never shift the card layout.
 */
@Composable
private fun MonitorStatusDot(status: MonitorStatus) {
    val (color, description) = when (status) {
        MonitorStatus.MONITORING -> MaterialTheme.colorScheme.primary to "Monitoring"
        MonitorStatus.MOTION -> Color(0xFFFFB300) to "Motion detected"
        MonitorStatus.DISABLED -> Color(0xFF5A6068) to "Not monitored"
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = description }
    )
}

/**
 * Tiny per-trigger indicators for every recording trigger configured on [device]:
 * bell = record on doorbell ring, walking man = record on motion (native or on-device
 * analysis; turns amber while motion is currently detected), speaker = record on noise.
 */
@Composable
private fun TriggerIndicators(device: DeviceEntity, monitorStatus: MonitorStatus) {
    val idleTint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (device.isEnabled && device.recordOnRing) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = "Records on doorbell ring",
                modifier = Modifier.size(16.dp),
                tint = idleTint
            )
        }
        if (device.isEnabled && (device.recordOnMotion || device.recordOnMotionOnDevice)) {
            val motionNow = monitorStatus == MonitorStatus.MOTION
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                contentDescription = if (motionNow) "Motion detected" else "Records on motion",
                modifier = Modifier.size(16.dp),
                tint = if (motionNow) Color(0xFFFFB300) else idleTint
            )
        }
        if (device.isEnabled && device.recordOnNoise) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Records on noise",
                modifier = Modifier.size(16.dp),
                tint = idleTint
            )
        }
    }
}
