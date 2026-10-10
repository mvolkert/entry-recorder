package io.github.mvolkert.entryrecorder.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mvolkert.entryrecorder.R

/**
 * Submenu for storage: the app's own archive (usage, retention, internal quota) and the SAF export folder
 * (its own quota and cleanup, transcode and auto-export switches). Owns the tree picker and the grant
 * re-check that previously lived on the hub.
 */
@Composable
fun SettingsStorageScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportFolderAccess by viewModel.exportFolderAccess.collectAsStateWithLifecycle()
    val storageUsage by viewModel.storageUsage.collectAsStateWithLifecycle()
    val externalUsageBytes by viewModel.externalUsageBytes.collectAsStateWithLifecycle()

    // Resolved here instead of inside the card: the display name costs a ContentProvider query, so the
    // presentational layer stays free of the export gateway. Keyed on the URI so it runs once per folder.
    val exportFolderUri = state.appSettings.exportFolderUri
    val exportFolderName = remember(exportFolderUri) {
        if (exportFolderUri.isBlank()) ""
        else viewModel.exportGateway.safFolderDisplayName(exportFolderUri.toUri())
    }

    val exportFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> viewModel.onExportFolderSelected(uri) }

    // Re-check the persisted export-folder grant on entry and whenever the folder changes: a revoked
    // permission or a deleted folder is otherwise invisible until the next export fails. The same tick
    // re-measures the folder, since walking a tree is far too expensive to do on a timer.
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
            internalUsage = storageUsage,
            externalUsageBytes = externalUsageBytes,
            exportFolderAccess = exportFolderAccess,
            exportFolderName = exportFolderName,
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
