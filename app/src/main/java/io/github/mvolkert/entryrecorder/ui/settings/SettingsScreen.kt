package io.github.mvolkert.entryrecorder.ui.settings

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mvolkert.entryrecorder.R

/**
 * Configuration hub: intercom devices, recording engine, storage & retention, alerts, appearance and
 * backup. Every section is its own card composable; this file keeps the SAF launchers and the
 * LazyColumn that orders the sections, and renders the ViewModel's one-shot events.
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
    val exportFolderAccess by viewModel.exportFolderAccess.collectAsStateWithLifecycle()
    val isTestingServer by viewModel.isTestingServer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val backupFileName = stringResource(R.string.settings_backup_filename)

    val exportFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> viewModel.onExportFolderSelected(uri) }

    // Backup & restore (settings + devices) to a user-chosen JSON file via SAF.
    val backupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportBackup(uri) }

    val backupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.restoreBackup(uri) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsUiEvent.Message -> Toast.makeText(
                    context,
                    event.text,
                    if (event.short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Re-check the persisted export-folder grant on entry and whenever the folder changes: a revoked
    // permission or a deleted folder is otherwise invisible until the next export fails.
    LaunchedEffect(state.appSettings.exportFolderUri) {
        viewModel.recheckExportFolder()
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
                        modifier = Modifier.animateItem(),
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
                    onSettingsChange = viewModel::updateSettings,
                    isTestingServer = isTestingServer,
                    onTestServer = {
                        viewModel.testServerConnection(
                            state.appSettings.serverBaseUrl,
                            state.appSettings.serverApiKey
                        )
                    }
                )
            }

            item { SettingsSectionHeader(R.string.settings_section_retention, Modifier.padding(top = 16.dp)) }

            item {
                SettingsStorageCard(
                    settings = state.appSettings,
                    onSettingsChange = viewModel::updateSettings,
                    totalStorageBytes = state.totalStorageBytes,
                    exportFolderAccess = exportFolderAccess,
                    onPickExportFolder = {
                        // Pre-select the current folder so re-granting lands where the user left off;
                        // a provider that cannot resolve it just opens at its root.
                        exportFolderPicker.launch(
                            state.appSettings.exportFolderUri.takeIf { it.isNotBlank() }?.toUri()
                        )
                    },
                    onClearExportFolder = viewModel::clearExportFolder,
                    onCleanupNow = viewModel::triggerCleanupNow
                )
            }

            item { SettingsSectionHeader(R.string.settings_section_appearance, Modifier.padding(top = 16.dp)) }

            item {
                SettingsAppearanceCard(
                    settings = state.appSettings,
                    onSettingsChange = viewModel::updateSettings
                )
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
