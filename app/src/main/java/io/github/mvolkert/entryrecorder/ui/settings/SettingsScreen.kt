package io.github.mvolkert.entryrecorder.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Doorbell
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.RecordingMode

/** Detail routes pushed from the hub; each maps to a sibling settings-detail destination in the NavHost. */
internal const val ROUTE_SETTINGS_DEVICES = "settings/devices"
internal const val ROUTE_SETTINGS_ENGINE = "settings/engine"
internal const val ROUTE_SETTINGS_STORAGE = "settings/storage"
internal const val ROUTE_SETTINGS_APPEARANCE = "settings/appearance"
internal const val ROUTE_SETTINGS_BACKUP = "settings/backup"

/**
 * Settings hub: one clickable row per category, each navigating into its own fullscreen submenu. The
 * section bodies and their SAF launchers live in the submenus now; this screen only lists the categories
 * with a live subtitle summarizing the current configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
    onNavigate: (String) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val devicesSubtitle = stringResource(R.string.settings_hub_devices_subtitle, state.devices.size)
    val engineSubtitle = stringResource(
        if (state.appSettings.recordingMode == RecordingMode.PYTHON_SERVER) R.string.settings_mode_server
        else R.string.settings_mode_local
    )
    val storageSubtitle = stringResource(
        R.string.settings_hub_storage_subtitle,
        Formatter.formatFileSize(context, state.totalStorageBytes)
    )
    val appearanceSubtitle = stringResource(
        when (state.appSettings.themeMode) {
            1 -> R.string.settings_theme_mode_light
            2 -> R.string.settings_theme_mode_dark
            else -> R.string.settings_theme_mode_system
        }
    )
    val backupSubtitle = stringResource(R.string.settings_hub_backup_subtitle)

    Scaffold(
        // Top inset handled by TopAppBar; bottom system inset by the host NavigationBar in
        // MainActivity. Nested Scaffold stays inset-free to avoid double-counting.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsNavRow(
                    icon = Icons.Default.Doorbell,
                    titleRes = R.string.settings_section_devices,
                    subtitle = devicesSubtitle,
                    onClick = { onNavigate(ROUTE_SETTINGS_DEVICES) }
                )
            }
            item {
                SettingsNavRow(
                    icon = Icons.Default.Videocam,
                    titleRes = R.string.settings_section_mode,
                    subtitle = engineSubtitle,
                    onClick = { onNavigate(ROUTE_SETTINGS_ENGINE) }
                )
            }
            item {
                SettingsNavRow(
                    icon = Icons.Default.CleaningServices,
                    titleRes = R.string.settings_section_retention,
                    subtitle = storageSubtitle,
                    onClick = { onNavigate(ROUTE_SETTINGS_STORAGE) }
                )
            }
            item {
                SettingsNavRow(
                    icon = Icons.Default.Palette,
                    titleRes = R.string.settings_section_appearance,
                    subtitle = appearanceSubtitle,
                    onClick = { onNavigate(ROUTE_SETTINGS_APPEARANCE) }
                )
            }
            item {
                SettingsNavRow(
                    icon = Icons.Default.Download,
                    titleRes = R.string.settings_section_backup,
                    subtitle = backupSubtitle,
                    onClick = { onNavigate(ROUTE_SETTINGS_BACKUP) }
                )
            }
            item {
                Text(
                    text = stringResource(R.string.settings_hub_footer_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
