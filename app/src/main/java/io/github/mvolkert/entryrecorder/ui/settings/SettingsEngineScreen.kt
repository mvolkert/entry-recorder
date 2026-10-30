package io.github.mvolkert.entryrecorder.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mvolkert.entryrecorder.R

/**
 * Submenu for where recordings are captured: on the phone or on the Python server. Hosts the existing
 * [SettingsEngineCard] and wires the shared ViewModel's server-test state and action.
 *
 * The motion-alert receiver section gets its own [MotionAlertPushViewModel] because its state lives in
 * SharedPreferences that the push service writes to, not in the Room settings row: the screen re-reads it every
 * time it is composed, so an endpoint that landed after the switch was flipped shows up without a restart.
 */
@Composable
fun SettingsEngineScreen(
    viewModel: SettingsViewModel,
    pushViewModel: MotionAlertPushViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isTestingServer by viewModel.isTestingServer.collectAsStateWithLifecycle()
    val pushState by pushViewModel.state.collectAsStateWithLifecycle()
    // Read during composition, not inside the click lambda: a CompositionLocal is only available while the
    // screen is being composed. `LocalActivity` rather than a cast of `LocalContext`, which is not always an
    // Activity and the cast is a lint error.
    val activity = LocalActivity.current
    // Registering is asynchronous and the distributor can hand the endpoint over at any moment, so entering
    // the screen is what re-reads it rather than a one-shot value captured at construction.
    LaunchedEffect(Unit) { pushViewModel.refresh() }

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
            },
            pushState = pushState,
            // The connector needs an Activity to show the distributor picker and nothing else; the screen hands
            // one over for the call, which is why the ViewModel never stores a Context.
            onReceiveToggle = { enabled -> pushViewModel.setReceiveEnabled(enabled, activity) },
        )
    }
}
