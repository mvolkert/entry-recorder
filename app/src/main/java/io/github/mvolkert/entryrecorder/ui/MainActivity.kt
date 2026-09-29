package io.github.mvolkert.entryrecorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.service.IntercomMonitorService
import io.github.mvolkert.entryrecorder.ui.live.LiveCamerasScreen
import io.github.mvolkert.entryrecorder.ui.recordings.RecordingsScreen
import io.github.mvolkert.entryrecorder.ui.settings.SettingsScreen
import io.github.mvolkert.entryrecorder.ui.settings.SettingsViewModel
import io.github.mvolkert.entryrecorder.ui.theme.AppTheme
import kotlinx.coroutines.launch

sealed class Screen(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    object Live : Screen("live", R.string.nav_live, Icons.Default.Videocam)
    object Recordings : Screen("recordings", R.string.nav_recordings, Icons.Default.VideoLibrary)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Monitor service can be started or refreshed
        IntercomMonitorService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestRequiredPermissions()
        IntercomMonitorService.start(this)

        setContent {
            // The accent palette lives in persisted settings, so the theme is applied above the
            // scaffold and the same Activity-scoped SettingsViewModel feeds both the theme and the
            // Settings screen (single source of truth for the chosen accent).
            val settingsViewModel: SettingsViewModel = viewModel()
            val settingsState by settingsViewModel.uiState.collectAsState()
            AppTheme(accentIndex = settingsState.appSettings.themeAccentIndex) {
                MainAppScaffold(settingsViewModel)
            }
        }
    }

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

    @Composable
    fun MainAppScaffold(settingsViewModel: SettingsViewModel) {
        val items = listOf(Screen.Live, Screen.Recordings, Screen.Settings)
        // Swipeable navigation: the pager owns the selected index and the NavigationBar mirrors it.
        // Per-screen state survives swipes because each screen's ViewModel is Activity-scoped.
        val pagerState = rememberPagerState(pageCount = { items.size })
        val scope = rememberCoroutineScope()

        Scaffold(
            // Outer scaffold only hosts the bottom NavigationBar. Insets are set to zero so the
            // system bars (status + navigation) are NOT added here; each destination's own
            // Scaffold/TopAppBar consumes the top inset, and the NavigationBar applies the bottom
            // inset itself. This avoids double-counting insets when nesting per-screen Scaffold(s).
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                NavigationBar {
                    items.forEachIndexed { index, screen ->
                        val label = stringResource(screen.labelRes)
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = label) },
                            label = { Text(label) },
                            selected = pagerState.currentPage == index,
                            onClick = {
                                if (pagerState.currentPage != index) {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) { page ->
                when (page) {
                    // Recording status + devices arrive as observable state via LiveViewModel.
                    0 -> LiveCamerasScreen()
                    1 -> RecordingsScreen()
                    else -> SettingsScreen(viewModel = settingsViewModel)
                }
            }
        }
    }
}
