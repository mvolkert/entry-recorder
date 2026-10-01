package io.github.mvolkert.entryrecorder.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.live.LiveCamerasScreen
import io.github.mvolkert.entryrecorder.ui.recordings.RecordingsScreen
import io.github.mvolkert.entryrecorder.ui.settings.DeviceEditScreen
import io.github.mvolkert.entryrecorder.ui.settings.NEW_DEVICE_ID
import io.github.mvolkert.entryrecorder.ui.settings.SettingsScreen
import io.github.mvolkert.entryrecorder.ui.settings.SettingsViewModel
import io.github.mvolkert.entryrecorder.ui.theme.AppMotion
import kotlinx.coroutines.launch
import kotlin.math.abs

sealed class Screen(@StringRes val labelRes: Int, val icon: ImageVector) {
    object Live : Screen(R.string.nav_live, Icons.Default.Videocam)
    object Recordings : Screen(R.string.nav_recordings, Icons.Default.VideoLibrary)
    object Settings : Screen(R.string.nav_settings, Icons.Default.Settings)
}

// NavHost graph below the pager. "main" keeps the swipeable 3-tab experience; editing a device is a
// sibling fullscreen destination, so the bottom NavigationBar is not shown while the form is open.
private const val ROUTE_MAIN = "main"
private const val ROUTE_DEVICE_EDIT = "device_edit/{deviceId}"
private const val ARG_DEVICE_ID = "deviceId"

@Composable
fun AppNavHost(settingsViewModel: SettingsViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_MAIN) {
        composable(ROUTE_MAIN) {
            MainAppScaffold(
                settingsViewModel = settingsViewModel,
                onEditDevice = { id -> navController.navigate("device_edit/$id") },
                onAddDevice = { navController.navigate("device_edit/$NEW_DEVICE_ID") }
            )
        }
        composable(
            route = ROUTE_DEVICE_EDIT,
            arguments = listOf(navArgument(ARG_DEVICE_ID) { type = NavType.LongType })
        ) { entry ->
            val deviceId = entry.arguments?.getLong(ARG_DEVICE_ID) ?: NEW_DEVICE_ID
            DeviceEditScreen(
                viewModel = settingsViewModel,
                deviceId = deviceId,
                onDone = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun MainAppScaffold(
    settingsViewModel: SettingsViewModel,
    onEditDevice: (Long) -> Unit,
    onAddDevice: () -> Unit
) {
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
                    val selected = pagerState.currentPage == index
                    // Expressive emphasis: the selected nav icon springs slightly larger.
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.12f else 1f,
                        animationSpec = AppMotion.emphasis,
                        label = "navIconScale",
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                screen.icon,
                                contentDescription = label,
                                modifier = Modifier.scale(iconScale),
                            )
                        },
                        label = { Text(label) },
                        selected = selected,
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Expressive page transition: the off-center page scales down and fades as it
                    // leaves the viewport. The scroll offset is a @FrequentlyChangingValue, so it is
                    // read inside the graphicsLayer lambda (draw phase) rather than in composition.
                    .graphicsLayer {
                        val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                            .coerceIn(-1f, 1f)
                        val fraction = abs(pageOffset)
                        val scale = 1f - (1f - AppMotion.PAGE_MIN_SCALE) * fraction
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - (1f - AppMotion.PAGE_MIN_ALPHA) * fraction
                    }
            ) {
                when (page) {
                    // Recording status + devices arrive as observable state via LiveViewModel.
                    0 -> LiveCamerasScreen()
                    1 -> RecordingsScreen()
                    else -> SettingsScreen(
                        viewModel = settingsViewModel,
                        onEditDevice = onEditDevice,
                        onAddDevice = onAddDevice
                    )
                }
            }
        }
    }
}
