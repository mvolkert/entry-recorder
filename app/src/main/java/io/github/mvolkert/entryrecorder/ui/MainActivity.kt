package io.github.mvolkert.entryrecorder.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.mvolkert.entryrecorder.data.local.prefs.OnboardingStore
import io.github.mvolkert.entryrecorder.service.IntercomMonitorService
import io.github.mvolkert.entryrecorder.ui.onboarding.OnboardingScreen
import io.github.mvolkert.entryrecorder.ui.onboarding.shouldShowOnboarding
import io.github.mvolkert.entryrecorder.ui.settings.SettingsViewModel
import io.github.mvolkert.entryrecorder.ui.theme.AppTheme
import io.github.mvolkert.entryrecorder.ui.theme.ThemeMode
import io.github.mvolkert.entryrecorder.ui.theme.UiModePrefs
import io.github.mvolkert.entryrecorder.ui.theme.themeModeAt
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by viewModels()

    /**
     * Injected rather than reached through [io.github.mvolkert.entryrecorder.data.local.prefs.OnboardingPrefs]
     * directly: the store is the one owner of that file, and this is its second reader.
     */
    @Inject lateinit var onboardingStore: OnboardingStore

    // The activity's own resources carry the uiMode override below, so once attachBaseContext has
    // run they can no longer answer what the *device* setting is. Captured before the override;
    // in ThemeMode.System the framework recreates us on a device flip, which refreshes it.
    private var systemNightAtLaunch = false

    /**
     * Whether the window owes the first-run flow. Decided from synchronous preferences before the first
     * frame, and the one fact it reads is the one the flow writes — see [shouldShowOnboarding].
     */
    private val showOnboarding = MutableStateFlow(false)

    /** Where the tabs hand the user off once the intro is done; see [TabLanding]. */
    private val tabLanding = MutableStateFlow(TabLanding())

    override fun attachBaseContext(newBase: Context) {
        val mode = themeModeAt(UiModePrefs.themeMode(newBase))
        systemNightAtLaunch = newBase.resources.configuration.isNight()
        if (mode != ThemeMode.System) {
            val config = Configuration(newBase.resources.configuration)
            config.uiMode = config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
                (if (mode == ThemeMode.Dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            // Must be applied before super.attachBaseContext so the window theme — and with it the
            // Android 12+ splash and the pre-12 windowBackground — resolves the matching day/night
            // resources instead of the device setting.
            applyOverrideConfiguration(config)
        }
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        showOnboarding.value = shouldShowOnboarding(isCompleted = onboardingStore.isCompleted())
        IntercomMonitorService.start(this)

        lifecycleScope.launch {
            var previous: Int? = null
            settingsViewModel.uiState
                .map { it.appSettings.themeMode }
                .distinctUntilChanged()
                .collect { mode ->
                    val prev = previous
                    previous = mode
                    // Recreate only when the *framework* resources have to flip (values-night for the
                    // window background/dialogs); Compose re-themes without help from the config.
                    if (prev != null && nightFor(themeModeAt(prev)) != nightFor(themeModeAt(mode))) {
                        recreate()
                    }
                }
        }

        setContent {
            // The accent palette lives in persisted settings, so the theme is applied above the
            // scaffold and the same Activity-scoped SettingsViewModel feeds both the theme and the
            // Settings screen (single source of truth for the chosen accent).
            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val settings = settingsState.appSettings
            AppTheme(
                themeMode = settings.themeMode,
                primaryIndex = settings.themePrimaryIndex,
                secondaryIndex = settings.themeSecondaryIndex,
                tertiaryIndex = settings.themeTertiaryIndex,
                useDynamicColor = settings.themeUseDynamicColor,
            ) {
                val onboarding by showOnboarding.collectAsStateWithLifecycle()
                val landing by tabLanding.collectAsStateWithLifecycle()
                if (onboarding) {
                    OnboardingScreen(
                        onFinished = { landOnDevices ->
                            showOnboarding.value = false
                            tabLanding.update {
                                TabLanding(
                                    pageIndex = if (landOnDevices) DevicesTabIndex else LiveTabIndex,
                                    requestId = it.requestId + 1,
                                )
                            }
                        },
                        // Same refresh the launch-time dialog used to fire on: the mic and the
                        // notification channel are both inputs the monitor re-reads.
                        onPermissionsSettled = { IntercomMonitorService.start(this@MainActivity) },
                    )
                } else {
                    AppRoot(
                        settingsViewModel = settingsViewModel,
                        onboardingLanding = landing,
                        onResetOnboarding = ::resetOnboarding,
                    )
                }
            }
        }
    }

    /**
     * Settings' way back into the flow. Wipes the recorded answer and both denial counters before opening
     * it, so a replay starts from the system sheet rather than from "Open phone settings", and so a process
     * death during a replay cannot strand the install on the tabs with a first run nobody finished.
     */
    private fun resetOnboarding() {
        onboardingStore.reset()
        showOnboarding.value = true
    }

    private fun nightFor(mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> systemNightAtLaunch
    }

    private fun Configuration.isNight(): Boolean =
        uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
