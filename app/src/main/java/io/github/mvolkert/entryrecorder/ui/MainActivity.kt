package io.github.mvolkert.entryrecorder.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.mvolkert.entryrecorder.service.IntercomMonitorService
import io.github.mvolkert.entryrecorder.ui.settings.SettingsViewModel
import io.github.mvolkert.entryrecorder.ui.theme.AppTheme
import io.github.mvolkert.entryrecorder.ui.theme.ThemeMode
import io.github.mvolkert.entryrecorder.ui.theme.UiModePrefs
import io.github.mvolkert.entryrecorder.ui.theme.themeModeAt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by viewModels()

    // The activity's own resources carry the uiMode override below, so once attachBaseContext has
    // run they can no longer answer what the *device* setting is. Captured before the override;
    // in ThemeMode.System the framework recreates us on a device flip, which refreshes it.
    private var systemNightAtLaunch = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Monitor service can be started or refreshed
        IntercomMonitorService.start(this)
    }

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

        requestRequiredPermissions()
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
            ) {
                AppNavHost(settingsViewModel)
            }
        }
    }

    private fun nightFor(mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> systemNightAtLaunch
    }

    private fun Configuration.isNight(): Boolean =
        uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
