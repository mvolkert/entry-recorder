package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mvolkert.entryrecorder.R

/**
 * Submenu for appearance: color mode and accent presets. Hosts the existing [SettingsAppearanceCard],
 * which owns the role-customize dialog internally.
 */
@Composable
fun SettingsAppearanceScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsSubscreenScaffold(
        titleRes = R.string.settings_section_appearance,
        onBack = onBack,
        events = viewModel.events,
    ) {
        SettingsAppearanceCard(
            settings = state.appSettings,
            onSettingsChange = viewModel::updateSettings
        )
    }
}
