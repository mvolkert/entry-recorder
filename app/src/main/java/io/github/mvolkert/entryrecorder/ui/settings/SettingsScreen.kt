package io.github.mvolkert.entryrecorder.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.util.ExportFolderAccess

/** Detail routes pushed from the hub; each maps to a sibling settings-detail destination in the NavHost. */
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
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigate: (String) -> Unit = {},
    onReplayOnboarding: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportFolderAccess by viewModel.exportFolderAccess.collectAsStateWithLifecycle()
    val storageUsage by viewModel.storageUsage.collectAsStateWithLifecycle()
    val externalUsageBytes by viewModel.externalUsageBytes.collectAsStateWithLifecycle()
    val exportFailure by viewModel.exportFailure.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val engineSubtitle = stringResource(
        if (state.appSettings.recordingMode == RecordingMode.PYTHON_SERVER) R.string.settings_mode_server
        else R.string.settings_mode_local
    )
    // Two numbers because the two halves are cleaned independently. The export half names its own state
    // instead of showing zero: a folder that is configured but currently unreachable is something the user
    // has to notice, and "0 B" there would read as an empty archive.
    val externalValue = when {
        // A retained write failure takes priority over the byte count: while exports are stalling the
        // size is stale, and the actionable fact is that saving stopped landing.
        exportFailure != null -> stringResource(R.string.settings_hub_storage_export_failed)

        state.appSettings.exportFolderUri.isBlank() ->
            stringResource(R.string.settings_hub_storage_no_folder)

        exportFolderAccess == ExportFolderAccess.OK || exportFolderAccess == ExportFolderAccess.UNKNOWN ->
            externalUsageBytes?.let { Formatter.formatFileSize(context, it) }
                ?: stringResource(R.string.settings_hub_storage_measuring)

        else -> stringResource(R.string.settings_hub_storage_needs_access)
    }
    val storageSubtitle = stringResource(
        R.string.settings_hub_storage_subtitle,
        Formatter.formatFileSize(context, storageUsage.totalBytes),
        externalValue,
    )
    val appearanceSubtitle = stringResource(
        when (state.appSettings.themeMode) {
            1 -> R.string.settings_theme_mode_light
            2 -> R.string.settings_theme_mode_dark
            else -> R.string.settings_theme_mode_system
        }
    )
    val backupSubtitle = stringResource(R.string.settings_hub_backup_subtitle)

    val scheme = MaterialTheme.colorScheme
    // Lifts the bar to its scrolled tone whenever the rows do pass under it; on a tall window where the
    // four rows never scroll, the state stays at rest and the bar keeps `surface`.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        // Top inset handled by TopAppBar; bottom system inset by the host NavigationBar in
        // MainActivity. Nested Scaffold stays inset-free to avoid double-counting.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Text(stringResource(R.string.settings_title), modifier = Modifier.semantics { heading() })
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsNavRow(
                    icon = Icons.Default.Videocam,
                    titleRes = R.string.settings_section_mode,
                    subtitle = engineSubtitle,
                    iconContainerColor = scheme.secondaryContainer,
                    iconContentColor = scheme.onSecondaryContainer,
                    onClick = { onNavigate(ROUTE_SETTINGS_ENGINE) }
                )
            }
            item {
                SettingsNavRow(
                    icon = Icons.Default.CleaningServices,
                    titleRes = R.string.settings_section_retention,
                    subtitle = storageSubtitle,
                    iconContainerColor = scheme.tertiaryContainer,
                    iconContentColor = scheme.onTertiaryContainer,
                    onClick = { onNavigate(ROUTE_SETTINGS_STORAGE) }
                )
            }
            item {
                SettingsNavRow(
                    icon = Icons.Default.Palette,
                    titleRes = R.string.settings_section_appearance,
                    subtitle = appearanceSubtitle,
                    iconContainerColor = scheme.secondaryContainer,
                    iconContentColor = scheme.onSecondaryContainer,
                    onClick = { onNavigate(ROUTE_SETTINGS_APPEARANCE) }
                )
            }
            item {
                SettingsNavRow(
                    icon = Icons.Default.Download,
                    titleRes = R.string.settings_section_backup,
                    subtitle = backupSubtitle,
                    iconContainerColor = scheme.surfaceContainerHighest,
                    iconContentColor = scheme.onSurfaceVariant,
                    onClick = { onNavigate(ROUTE_SETTINGS_BACKUP) }
                )
            }
            item {
                SettingsNavRow(
                    icon = Icons.Default.Refresh,
                    titleRes = R.string.settings_rerun_onboarding_title,
                    subtitle = stringResource(R.string.settings_rerun_onboarding_subtitle),
                    iconContainerColor = scheme.secondaryContainer,
                    iconContentColor = scheme.onSecondaryContainer,
                    onClick = onReplayOnboarding,
                )
            }
        }
    }
}
