package io.github.mvolkert.entryrecorder.ui.settings

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Doorbell
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.util.ExportHelper
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var editingDevice by remember { mutableStateOf<DeviceEntity?>(null) }
    var showAddDeviceDialog by remember { mutableStateOf(false) }
    var isTestingServer by remember { mutableStateOf(false) }

    val exportFolderFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val exportFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val previous = state.appSettings.exportFolderUri
                if (previous.isNotBlank() && previous != uri.toString()) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(previous.toUri(), exportFolderFlags)
                    }
                }
                context.contentResolver.takePersistableUriPermission(uri, exportFolderFlags)
                viewModel.updateSettings(state.appSettings.copy(exportFolderUri = uri.toString()))
            } catch (_: SecurityException) {
                Toast.makeText(context, "Could not persist export folder permission", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun clearExportFolder() {
        val previous = state.appSettings.exportFolderUri
        if (previous.isNotBlank()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(previous.toUri(), exportFolderFlags)
            }
        }
        viewModel.updateSettings(state.appSettings.copy(exportFolderUri = ""))
    }

    // Backup & restore (settings + devices) to a user-chosen JSON file via SAF.
    val backupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportBackup(uri) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.restoreBackup(uri) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        // Top inset handled by TopAppBar; bottom system inset by the host NavigationBar in
        // MainActivity. Nested Scaffold stays inset-free to avoid double-counting.
        contentWindowInsets = WindowInsets(0),
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
                                    ) { _, msg ->
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

                        // Storage Quota (purge oldest unprotected recordings above this limit)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Storage Quota", fontWeight = FontWeight.Medium)
                                Text(
                                    text = "Purge oldest recordings above ${state.appSettings.maxStorageUsageMb / 1024} GB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row {
                                IconButton(
                                    onClick = {
                                        val newMb = (state.appSettings.maxStorageUsageMb - 1024L).coerceAtLeast(1024L)
                                        viewModel.updateSettings(state.appSettings.copy(maxStorageUsageMb = newMb))
                                    }
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "Decrease")
                                }
                                Text(
                                    "${state.appSettings.maxStorageUsageMb / 1024}GB",
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = {
                                        val newMb = state.appSettings.maxStorageUsageMb + 1024L
                                        viewModel.updateSettings(state.appSettings.copy(maxStorageUsageMb = newMb))
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

                        // Transcode-on-export toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Transcode to H.264 on Export", fontWeight = FontWeight.Medium)
                                Text(
                                    text = "Converts recordings for universal playback when sharing/saving. Uses CPU & battery only during export.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.appSettings.transcodeOnExport,
                                onCheckedChange = {
                                    viewModel.updateSettings(state.appSettings.copy(transcodeOnExport = it))
                                }
                            )
                        }

                        // Auto-export (mirror originals at finalization) toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Export Finished Recordings", fontWeight = FontWeight.Medium)
                                Text(
                                    text = "Copies each recording's original MJPEG MKV into the export folder " +
                                            "as soon as it finishes — a complete, sync-friendly archive " +
                                            "(plays in VLC, not every gallery app). Best-effort; the app " +
                                            "always keeps its own copy.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.appSettings.autoExportOnFinalize,
                                onCheckedChange = {
                                    viewModel.updateSettings(state.appSettings.copy(autoExportOnFinalize = it))
                                }
                            )
                        }

                        // Export folder (SAF) picker
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Export Folder", fontWeight = FontWeight.Medium)
                            Text(
                                text = "Choose a folder where \"Export to folder\" saves a single file " +
                                        "per recording (H.264 when transcoding is on) and where " +
                                        "\"Auto-Export\" mirrors the original MKVs. The Share button only " +
                                        "shares and never writes here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val folder = state.appSettings.exportFolderUri
                            if (folder.isNotBlank()) {
                                Text(
                                    text = "Selected: ${ExportHelper.safFolderDisplayName(folder.toUri())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { exportFolderPicker.launch(null) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (folder.isBlank()) "Choose folder" else "Change folder")
                                }
                                if (folder.isNotBlank()) {
                                    TextButton(onClick = { clearExportFolder() }) {
                                        Text("Remove", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
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
                            Text("Play Sound on Doorbell Ring")
                            Switch(
                                checked = state.appSettings.soundOnRing,
                                onCheckedChange = {
                                    viewModel.updateSettings(state.appSettings.copy(soundOnRing = it))
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Vibrate on Doorbell Ring")
                            Switch(
                                checked = state.appSettings.vibrateOnRing,
                                onCheckedChange = {
                                    viewModel.updateSettings(state.appSettings.copy(vibrateOnRing = it))
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Wake Screen on Noise Detection")
                            Switch(
                                checked = state.appSettings.wakeOnNoise,
                                onCheckedChange = {
                                    viewModel.updateSettings(state.appSettings.copy(wakeOnNoise = it))
                                }
                            )
                        }
                    }
                }
            }

            // Backup & Restore Section
            item {
                Text(
                    text = "Backup & Restore",
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
                            text = "Export or import your app settings and configured devices as a single file. " +
                                    "Recordings are not included. The file stores device/SIP credentials in " +
                                    "plain text, so keep it somewhere private.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { backupExportLauncher.launch("entry-recorder-backup.json") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Export backup")
                        }
                        OutlinedButton(
                            onClick = { backupImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Restore from backup")
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
