package io.github.mvolkert.entryrecorder.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mvolkert.entryrecorder.R

/**
 * Submenu for JSON backup & restore of settings + devices. Owns the two SAF launchers (create-document
 * for export, open-document for import) that previously lived on the hub; the ViewModel does the work.
 */
@Composable
fun SettingsBackupScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val backupFileName = stringResource(R.string.settings_backup_filename)

    val backupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportBackup(uri) }

    val backupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.restoreBackup(uri) }

    SettingsSubscreenScaffold(
        titleRes = R.string.settings_section_backup,
        onBack = onBack,
        events = viewModel.events,
    ) {
        SettingsBackupCard(
            onExportBackup = { backupExportLauncher.launch(backupFileName) },
            onRestoreBackup = {
                backupImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
            }
        )
    }
}
