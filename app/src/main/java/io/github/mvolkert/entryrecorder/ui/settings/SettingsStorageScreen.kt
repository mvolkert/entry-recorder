package io.github.mvolkert.entryrecorder.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mvolkert.entryrecorder.R

/**
 * Submenu for storage & retention: usage, retention/quota, transcode/auto-export switches and the
 * export folder. Owns the tree picker and the grant re-check that previously lived on the hub.
 */
@Composable
fun SettingsStorageScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportFolderAccess by viewModel.exportFolderAccess.collectAsStateWithLifecycle()

    val exportFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> viewModel.onExportFolderSelected(uri) }

    // Re-check the persisted export-folder grant on entry and whenever the folder changes: a revoked
    // permission or a deleted folder is otherwise invisible until the next export fails.
    LaunchedEffect(state.appSettings.exportFolderUri) {
        viewModel.recheckExportFolder()
    }

    SettingsSubscreenScaffold(
        titleRes = R.string.settings_section_retention,
        onBack = onBack,
        events = viewModel.events,
    ) {
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
}
