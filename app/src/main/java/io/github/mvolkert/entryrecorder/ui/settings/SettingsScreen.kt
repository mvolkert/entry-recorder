package io.github.mvolkert.entryrecorder.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity

/**
 * Configuration hub: intercom devices, recording engine, storage & retention, alerts, appearance and
 * backup. Every section is its own card composable; this file keeps the screen-level state (SAF
 * launchers, dialogs) and the LazyColumn that orders the sections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
    onEditDevice: (Long) -> Unit = {},
    onAddDevice: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val backupFileName = stringResource(R.string.settings_backup_filename)

    var isTestingServer by remember { mutableStateOf(false) }

    val updateSettings = { settings: AppSettingsEntity -> viewModel.updateSettings(settings) }

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
                onClick = onAddDevice,
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
            item { SettingsSectionHeader(R.string.settings_section_devices, Modifier.padding(top = 8.dp)) }

            if (state.devices.isEmpty()) {
                item { DevicesEmptyCard() }
            } else {
                items(state.devices, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        eventQuality = state.eventQualities[device.id],
                        snapshotQuality = state.snapshotQualities[device.id],
                        onEdit = { onEditDevice(device.id) },
                        onDelete = { viewModel.deleteDevice(device) }
                    )
                }
            }

            item { SettingsSectionHeader(R.string.settings_section_mode, Modifier.padding(top = 16.dp)) }

            item {
                SettingsEngineCard(
                    settings = state.appSettings,
                    onSettingsChange = updateSettings,
                    isTestingServer = isTestingServer,
                    onTestServer = {
                        isTestingServer = true
                        viewModel.testServerConnection(
                            state.appSettings.serverBaseUrl,
                            state.appSettings.serverApiKey
                        ) { _, msg ->
                            isTestingServer = false
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }

            item { SettingsSectionHeader(R.string.settings_section_retention, Modifier.padding(top = 16.dp)) }

            item {
                SettingsStorageCard(
                    settings = state.appSettings,
                    onSettingsChange = updateSettings,
                    totalStorageBytes = state.totalStorageBytes,
                    onPickExportFolder = { exportFolderPicker.launch(null) },
                    onClearExportFolder = { clearExportFolder() },
                    onCleanupNow = {
                        viewModel.triggerCleanupNow()
                        Toast.makeText(context, R.string.settings_toast_cleanup_triggered, Toast.LENGTH_SHORT).show()
                    }
                )
            }

            item { SettingsSectionHeader(R.string.settings_section_appearance, Modifier.padding(top = 16.dp)) }

            item {
                SettingsAppearanceCard(settings = state.appSettings, onSettingsChange = updateSettings)
            }

            item { SettingsSectionHeader(R.string.settings_section_backup, Modifier.padding(top = 16.dp)) }

            item {
                SettingsBackupCard(
                    onExportBackup = { backupExportLauncher.launch(backupFileName) },
                    onRestoreBackup = {
                        backupImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }
}
