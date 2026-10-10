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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.service.IntercomMonitorService
import io.github.mvolkert.entryrecorder.ui.settings.SettingsViewModel
import io.github.mvolkert.entryrecorder.ui.theme.AppTheme
import io.github.mvolkert.entryrecorder.ui.theme.ThemeMode
import io.github.mvolkert.entryrecorder.ui.theme.UiModePrefs
import io.github.mvolkert.entryrecorder.ui.theme.themeModeAt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by viewModels()

    // The activity's own resources carry the uiMode override below, so once attachBaseContext has
    // run they can no longer answer what the *device* setting is. Captured before the override;
    // in ThemeMode.System the framework recreates us on a device flip, which refreshes it.
    private var systemNightAtLaunch = false

    /** Runtime permissions still missing at launch; empty skips the rationale, non-empty gates the request behind it. */
    private var pendingPermissions: Array<String> = emptyArray()

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

        pendingPermissions = missingRequiredPermissions()
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
                if (pendingPermissions.isNotEmpty()) {
                    PermissionRationaleGate(
                        pendingPermissions = pendingPermissions,
                        onRequest = { permissionLauncher.launch(it) },
                    )
                }
                AppRoot(settingsViewModel)
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

    private fun missingRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }
}

/**
 * One-shot rationale shown before the launch-time permission request: the app needs the mic and
 * notifications for background monitoring, and asking cold invites a deny-without-understanding.
 * "Continue" runs the system dialog, "Skip" leaves the app degraded but working — the monitor
 * service was already started in [MainActivity.onCreate] either way. Survives rotation via
 * [rememberSaveable], so the system sheet is never gated twice per process.
 */
@Composable
private fun PermissionRationaleGate(
    pendingPermissions: Array<String>,
    onRequest: (Array<String>) -> Unit,
) {
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    if (acknowledged) return
    AlertDialog(
        onDismissRequest = { acknowledged = true },
        title = {
            Text(stringResource(R.string.permission_rationale_title), modifier = Modifier.semantics { heading() })
        },
        text = { Text(stringResource(R.string.permission_rationale_body)) },
        confirmButton = {
            TextButton(onClick = {
                acknowledged = true
                onRequest(pendingPermissions)
            }) { Text(stringResource(R.string.permission_rationale_continue)) }
        },
        dismissButton = {
            TextButton(onClick = { acknowledged = true }) {
                Text(stringResource(R.string.permission_rationale_skip))
            }
        },
    )
}
