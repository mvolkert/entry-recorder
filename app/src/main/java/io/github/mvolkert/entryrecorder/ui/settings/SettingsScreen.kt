package io.github.mvolkert.entryrecorder.ui.settings

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.ui.theme.accentPresets
import io.github.mvolkert.entryrecorder.util.ExportHelper
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val backupFileName = stringResource(R.string.settings_backup_filename)

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
                Toast.makeText(context, R.string.settings_toast_folder_permission_failed, Toast.LENGTH_LONG).show()
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
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDeviceDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_cd_add)) },
                text = { Text(stringResource(R.string.settings_add_device)) }
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
                    text = stringResource(R.string.settings_section_devices),
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
                            Text(stringResource(R.string.settings_devices_empty))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.settings_devices_empty_hint),
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
                    text = stringResource(R.string.settings_section_mode),
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
                        Text(stringResource(R.string.settings_mode_question))

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
                                label = { Text(stringResource(R.string.settings_mode_local)) },
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
                                label = { Text(stringResource(R.string.settings_mode_server)) },
                                leadingIcon = if (state.appSettings.recordingMode == RecordingMode.PYTHON_SERVER) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                        }

                        if (state.appSettings.recordingMode == RecordingMode.PYTHON_SERVER) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.settings_mode_server_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = state.appSettings.serverBaseUrl,
                                onValueChange = {
                                    viewModel.updateSettings(state.appSettings.copy(serverBaseUrl = it))
                                },
                                label = { Text(stringResource(R.string.settings_server_url_label)) },
                                placeholder = { Text(stringResource(R.string.settings_server_url_placeholder)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = state.appSettings.serverApiKey,
                                onValueChange = {
                                    viewModel.updateSettings(state.appSettings.copy(serverApiKey = it))
                                },
                                label = { Text(stringResource(R.string.settings_server_api_key)) },
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
                                    Text(stringResource(R.string.settings_server_testing))
                                } else {
                                    Icon(Icons.Default.WifiTethering, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.settings_server_test))
                                }
                            }
                        }
                    }
                }
            }

            // Storage & Retention Section
            item {
                Text(
                    text = stringResource(R.string.settings_section_retention),
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
                            text = stringResource(
                                R.string.settings_storage_usage,
                                Formatter.formatFileSize(context, state.totalStorageBytes)
                            ),
                            fontWeight = FontWeight.SemiBold
                        )

                        // Retention Days
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(stringResource(R.string.settings_retention_period), fontWeight = FontWeight.Medium)
                                Text(
                                    text = if (state.appSettings.retentionDays == 0)
                                        stringResource(R.string.settings_retention_indefinite)
                                    else pluralStringResource(
                                        R.plurals.settings_retention_days,
                                        state.appSettings.retentionDays,
                                        state.appSettings.retentionDays
                                    ),
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
                                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.settings_cd_decrease))
                                }
                                Text(
                                    stringResource(R.string.settings_retention_days_short, state.appSettings.retentionDays),
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = {
                                        val newDays = state.appSettings.retentionDays + 7
                                        viewModel.updateSettings(state.appSettings.copy(retentionDays = newDays))
                                    }
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_cd_increase))
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
                                Text(stringResource(R.string.settings_storage_quota), fontWeight = FontWeight.Medium)
                                Text(
                                    text = stringResource(
                                        R.string.settings_quota_purge,
                                        state.appSettings.maxStorageUsageMb / 1024
                                    ),
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
                                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.settings_cd_decrease))
                                }
                                Text(
                                    stringResource(R.string.settings_quota_gb, state.appSettings.maxStorageUsageMb / 1024),
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = {
                                        val newMb = state.appSettings.maxStorageUsageMb + 1024L
                                        viewModel.updateSettings(state.appSettings.copy(maxStorageUsageMb = newMb))
                                    }
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_cd_increase))
                                }
                            }
                        }

                        // Auto-Cleanup toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.settings_auto_cleanup))
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
                                Text(stringResource(R.string.settings_transcode_export), fontWeight = FontWeight.Medium)
                                Text(
                                    text = stringResource(R.string.settings_transcode_export_hint),
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
                                Text(stringResource(R.string.settings_auto_export), fontWeight = FontWeight.Medium)
                                Text(
                                    text = stringResource(R.string.settings_auto_export_hint),
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
                            Text(stringResource(R.string.settings_export_folder), fontWeight = FontWeight.Medium)
                            Text(
                                text = stringResource(R.string.settings_export_folder_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val folder = state.appSettings.exportFolderUri
                            if (folder.isNotBlank()) {
                                Text(
                                    text = stringResource(
                                        R.string.settings_export_folder_selected,
                                        ExportHelper.safFolderDisplayName(folder.toUri())
                                    ),
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
                                    Text(stringResource(if (folder.isBlank()) R.string.settings_folder_choose else R.string.settings_folder_change))
                                }
                                if (folder.isNotBlank()) {
                                    TextButton(onClick = { clearExportFolder() }) {
                                        Text(stringResource(R.string.settings_folder_remove), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        // Trigger cleanup now button
                        OutlinedButton(
                            onClick = {
                                viewModel.triggerCleanupNow()
                                Toast.makeText(context, R.string.settings_toast_cleanup_triggered, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_cleanup_now))
                        }
                    }
                }
            }

            // Screen & Alert Notifications
            item {
                Text(
                    text = stringResource(R.string.settings_section_alerts),
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
                            Text(stringResource(R.string.settings_wake_ring))
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
                            Text(stringResource(R.string.settings_sound_ring))
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
                            Text(stringResource(R.string.settings_vibrate_ring))
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
                            Text(stringResource(R.string.settings_wake_motion))
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
                            Text(stringResource(R.string.settings_wake_noise))
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

            // Appearance (accent color presets)
            item {
                Text(
                    text = stringResource(R.string.settings_section_appearance),
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
                            text = stringResource(R.string.settings_accent_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            accentPresets.forEachIndexed { index, palette ->
                                val selected = state.appSettings.themeAccentIndex == index
                                val label = stringResource(palette.labelRes)
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(palette.primary)
                                        .border(
                                            width = if (selected) 3.dp else 0.dp,
                                            color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            viewModel.updateSettings(
                                                state.appSettings.copy(themeAccentIndex = index)
                                            )
                                        }
                                        .semantics { contentDescription = label },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = stringResource(R.string.settings_accent_selected),
                                            tint = palette.onPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Backup & Restore Section
            item {
                Text(
                    text = stringResource(R.string.settings_section_backup),
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
                            text = stringResource(R.string.settings_backup_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { backupExportLauncher.launch(backupFileName) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_backup_export))
                        }
                        OutlinedButton(
                            onClick = { backupImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_backup_restore))
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
                    text = stringResource(
                        R.string.settings_device_ip_line,
                        device.ipAddress, device.httpPort, device.rtspPort
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(
                        R.string.settings_device_sip_line,
                        device.sipMode.name, device.sipLocalPort
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.settings_cd_edit))
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_cd_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
