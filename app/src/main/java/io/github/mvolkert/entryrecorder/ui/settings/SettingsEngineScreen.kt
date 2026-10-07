package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mvolkert.entryrecorder.R

/**
 * Submenu for where recordings are captured: on the phone or on the Python server. Hosts the existing
 * [SettingsEngineCard] and wires the shared ViewModel's server-test state and action.
 */
@Composable
fun SettingsEngineScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isTestingServer by viewModel.isTestingServer.collectAsStateWithLifecycle()

    SettingsSubscreenScaffold(
        titleRes = R.string.settings_section_mode,
        onBack = onBack,
        events = viewModel.events,
    ) {
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
}
