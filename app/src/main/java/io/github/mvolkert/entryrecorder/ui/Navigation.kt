package io.github.mvolkert.entryrecorder.ui

import androidx.annotation.StringRes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.adaptive.AdaptiveNavItem
import io.github.mvolkert.entryrecorder.ui.adaptive.AdaptiveScaffold
import io.github.mvolkert.entryrecorder.ui.adaptive.LocalWindowInfo
import io.github.mvolkert.entryrecorder.ui.adaptive.rememberWindowInfo
import io.github.mvolkert.entryrecorder.ui.live.LiveCamerasScreen
import io.github.mvolkert.entryrecorder.ui.recordings.RecordingsScreen
import io.github.mvolkert.entryrecorder.ui.settings.DeviceEditScreen
import io.github.mvolkert.entryrecorder.ui.settings.NEW_DEVICE_ID
import io.github.mvolkert.entryrecorder.ui.settings.SettingsScreen
import io.github.mvolkert.entryrecorder.ui.settings.SettingsViewModel
import io.github.mvolkert.entryrecorder.ui.theme.appMotionScheme

/**
 * A top-level destination reachable from either the bottom bar (Compact) or the rail (Medium+).
 * Each screen owns its own back stack slot inside the shared [NavHost] so switching tabs keeps the
 * per-screen scroll and any inner pushes intact.
 */
sealed class Screen(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    object Live : Screen("live", R.string.nav_live, Icons.Default.Videocam)
    object Recordings : Screen("recordings", R.string.nav_recordings, Icons.Default.VideoLibrary)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
}

private val TopLevelScreens = listOf(Screen.Live, Screen.Recordings, Screen.Settings)

// Editing a device is a sibling fullscreen route: neither bar nor rail is drawn while it is on top.
private const val ROUTE_DEVICE_EDIT = "device_edit/{deviceId}"
private const val ARG_DEVICE_ID = "deviceId"

/**
 * Root composable. Owns the [NavHostController], hoists the adaptive [io.github.mvolkert.entryrecorder.ui.adaptive.WindowInfo]
 * into a [LocalWindowInfo] provider, and wraps the NavHost inside an [AdaptiveScaffold] so the same
 * three destinations render either under a bottom [androidx.compose.material3.NavigationBar] or a
 * leading [androidx.compose.material3.NavigationRail] depending on window width.
 */
@Composable
fun AppRoot(settingsViewModel: SettingsViewModel) {
    val windowInfo = rememberWindowInfo()
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentEntry?.destination
    val selectedRoute = TopLevelScreens.firstOrNull { it.route == currentDestination?.route }?.route
    val showChrome = currentDestination?.route != ROUTE_DEVICE_EDIT
    val items = TopLevelScreens.map { AdaptiveNavItem(it.route, it.labelRes, it.icon) }
    // NavHost transition lambdas are not @Composable, so resolve the seam once here and capture it.
    val slideSpec = MaterialTheme.appMotionScheme.defaultSpatialSpec<IntOffset>()
    val fadeSpec = MaterialTheme.appMotionScheme.defaultEffectsSpec<Float>()

    CompositionLocalProvider(LocalWindowInfo provides windowInfo) {
        AdaptiveScaffold(
            items = items,
            selectedRoute = selectedRoute,
            showChrome = showChrome,
            onNavigate = { item -> navController.navigateToTopLevel(item.route) },
        ) { contentModifier ->
            NavHost(
                navController = navController,
                startDestination = Screen.Live.route,
                modifier = contentModifier.fillMaxSize(),
            ) {
                composable(Screen.Live.route) {
                    LiveCamerasScreen()
                }
                composable(Screen.Recordings.route) {
                    RecordingsScreen()
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onEditDevice = { id -> navController.navigate("device_edit/$id") },
                        onAddDevice = { navController.navigate("device_edit/$NEW_DEVICE_ID") },
                    )
                }
                composable(
                    route = ROUTE_DEVICE_EDIT,
                    arguments = listOf(navArgument(ARG_DEVICE_ID) { type = NavType.LongType }),
                    // M3 shared-axis X: the form is a peer screen, not a dialog, so it enters from
                    // the leading edge and reverses on pop.
                    enterTransition = {
                        slideInHorizontally(animationSpec = slideSpec, initialOffsetX = { it / 4 }) +
                            fadeIn(animationSpec = fadeSpec)
                    },
                    exitTransition = {
                        slideOutHorizontally(animationSpec = slideSpec, targetOffsetX = { -it / 4 }) +
                            fadeOut(animationSpec = fadeSpec)
                    },
                    popEnterTransition = {
                        slideInHorizontally(animationSpec = slideSpec, initialOffsetX = { -it / 4 }) +
                            fadeIn(animationSpec = fadeSpec)
                    },
                    popExitTransition = {
                        slideOutHorizontally(animationSpec = slideSpec, targetOffsetX = { it / 4 }) +
                            fadeOut(animationSpec = fadeSpec)
                    },
                ) { entry ->
                    val deviceId = entry.arguments?.getLong(ARG_DEVICE_ID) ?: NEW_DEVICE_ID
                    DeviceEditScreen(
                        viewModel = settingsViewModel,
                        deviceId = deviceId,
                        onDone = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/**
 * Canonical AndroidX multi-backstack navigation between top-level destinations: pop everything above
 * the start destination but save its state, launch a single instance of the target, and restore the
 * target's saved state (scroll position, inner pushes) on arrival.
 */
private fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
