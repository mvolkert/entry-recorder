package io.github.mvolkert.entryrecorder.ui.settings

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.RecordingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var editingDevice by remember { mutableStateOf<DeviceEntity?>(null) }
    var showAddDeviceDialog by remember { mutableStateOf(false) }
    var isTestingServer by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Devices", fontWeight = FontWeight.Bold) }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDeviceDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = "Add") },
                text = { Text("Add Intercom") }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Devices Section Header
            item {
                Text(
                    text = "Configured Intercom Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (state.devices.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("No devices added yet.")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Add your 2N IP Verso intercom with its local LAN IP address to start recording and receiving calls.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(state.devices, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        onEdit = { editingDevice = device },
                        onDelete = { viewModel.deleteDevice(device) }
                    )
                }
            }

            // Recording Engine & Destination Section
            item {
                Text(
                    text = "Recording Mode & Destination",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Where should video recordings be recorded and stored?")

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.appSettings.recordingMode == RecordingMode.APP_LOCAL,
                                onClick = {
                                    viewModel.updateSettings(
                                        state.appSettings.copy(recordingMode = RecordingMode.APP_LOCAL)
                                    )
                                },
                                label = { Text("📱 In-App (Default)") },
                                leadingIcon = if (state.appSettings.recordingMode == RecordingMode.APP_LOCAL) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )

                            FilterChip(
                                selected = state.appSettings.recordingMode == RecordingMode.PYTHON_SERVER,
                                onClick = {
                                    viewModel.updateSettings(
                                        state.appSettings.copy(recordingMode = RecordingMode.PYTHON_SERVER)
                                    )
                                },
                                label = { Text("🐍 Python Server") },
                                leadingIcon = if (state.appSettings.recordingMode == RecordingMode.PYTHON_SERVER) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                        }

                        if (state.appSettings.recordingMode == RecordingMode.PYTHON_SERVER) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Videos will be recorded on the centralized Python server backend with web interface. If the server is unreachable, recording automatically falls back to in-app storage.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = state.appSettings.serverBaseUrl,
                                onValueChange = {
                                    viewModel.updateSettings(state.appSettings.copy(serverBaseUrl = it))
                                },
                                label = { Text("Python Server URL") },
                                placeholder = { Text("http://192.168.1.100:8000") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = state.appSettings.serverApiKey,
                                onValueChange = {
                                    viewModel.updateSettings(state.appSettings.copy(serverApiKey = it))
                                },
                                label = { Text("API Key (Optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            OutlinedButton(
                                onClick = {
                                    isTestingServer = true
                                    viewModel.testServerConnection(
                                        state.appSettings.serverBaseUrl,
                                        state.appSettings.serverApiKey
                                    ) { success, msg ->
                                        isTestingServer = false
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isTestingServer
                            ) {
                                if (isTestingServer) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Testing connection...")
                                } else {
                                    Icon(Icons.Default.WifiTethering, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Test Server Connection")
                                }
                            }
                        }
                    }
                }
            }

            // Storage & Retention Section
            item {
                Text(
                    text = "Video Retention & Auto-Cleanup",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Current storage usage: ${Formatter.formatFileSize(context, state.totalStorageBytes)}",
                            fontWeight = FontWeight.SemiBold
                        )

                        // Retention Days
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Retention Period", fontWeight = FontWeight.Medium)
                                Text(
                                    text = if (state.appSettings.retentionDays == 0) "Keep indefinitely" else "Keep videos for ${state.appSettings.retentionDays} days",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row {
                                IconButton(
                                    onClick = {
                                        val newDays = (state.appSettings.retentionDays - 7).coerceAtLeast(0)
                                        viewModel.updateSettings(state.appSettings.copy(retentionDays = newDays))
                                    }
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "Decrease")
                                }
                                Text(
                                    "${state.appSettings.retentionDays}d",
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = {
                                        val newDays = state.appSettings.retentionDays + 7
                                        viewModel.updateSettings(state.appSettings.copy(retentionDays = newDays))
                                    }
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Increase")
                                }
                            }
                        }

                        // Auto-Cleanup toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Automatic Daily Cleanup")
                            Switch(
                                checked = state.appSettings.autoCleanupEnabled,
                                onCheckedChange = {
                                    viewModel.updateSettings(state.appSettings.copy(autoCleanupEnabled = it))
                                }
                            )
                        }

                        // Trigger cleanup now button
                        OutlinedButton(
                            onClick = {
                                viewModel.triggerCleanupNow()
                                Toast.makeText(context, "Storage cleanup triggered", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Run Storage Cleanup Now")
                        }
                    }
                }
            }

            // Screen & Alert Notifications
            item {
                Text(
                    text = "Alerts & Lockscreen Behavior",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Wake Lockscreen on Doorbell Ring")
                            Switch(
                                checked = state.appSettings.wakeOnRing,
                                onCheckedChange = {
                                    viewModel.updateSettings(state.appSettings.copy(wakeOnRing = it))
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Wake Screen on Motion Detection")
                            Switch(
                                checked = state.appSettings.wakeOnMotion,
                                onCheckedChange = {
                                    viewModel.updateSettings(state.appSettings.copy(wakeOnMotion = it))
                                }
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }

    if (showAddDeviceDialog) {
        DeviceEditDialog(
            initialDevice = null,
            onDismiss = { showAddDeviceDialog = false },
            onSave = { dev ->
                viewModel.saveDevice(dev)
                showAddDeviceDialog = false
            }
        )
    }

    editingDevice?.let { dev ->
        DeviceEditDialog(
            initialDevice = dev,
            onDismiss = { editingDevice = null },
            onSave = { updated ->
                viewModel.saveDevice(updated)
                editingDevice = null
            }
        )
    }
}

@Composable
fun DeviceCard(
    device: DeviceEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Doorbell,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "IP: ${device.ipAddress} (HTTP: ${device.httpPort}, RTSP: ${device.rtspPort})",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "SIP: ${device.sipMode.name} (Port ${device.sipLocalPort})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
