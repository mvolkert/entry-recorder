package io.github.mvolkert.entryrecorder.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
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
import io.github.mvolkert.entryrecorder.ui.adaptive.appContentMaxWidth
import io.github.mvolkert.entryrecorder.ui.adaptive.rememberWindowInfo
import io.github.mvolkert.entryrecorder.ui.live.LiveCamerasScreen
import io.github.mvolkert.entryrecorder.ui.recordings.RecordingsScreen
import io.github.mvolkert.entryrecorder.ui.settings.DeviceEditScreen
import io.github.mvolkert.entryrecorder.ui.settings.NEW_DEVICE_ID
import io.github.mvolkert.entryrecorder.ui.settings.ROUTE_SETTINGS_APPEARANCE
import io.github.mvolkert.entryrecorder.ui.settings.ROUTE_SETTINGS_BACKUP
import io.github.mvolkert.entryrecorder.ui.settings.ROUTE_SETTINGS_DEVICES
import io.github.mvolkert.entryrecorder.ui.settings.ROUTE_SETTINGS_ENGINE
import io.github.mvolkert.entryrecorder.ui.settings.ROUTE_SETTINGS_STORAGE
import io.github.mvolkert.entryrecorder.ui.settings.SettingsAppearanceScreen
import io.github.mvolkert.entryrecorder.ui.settings.SettingsBackupScreen
import io.github.mvolkert.entryrecorder.ui.settings.SettingsDevicesScreen
import io.github.mvolkert.entryrecorder.ui.settings.SettingsEngineScreen
import io.github.mvolkert.entryrecorder.ui.settings.SettingsScreen
import io.github.mvolkert.entryrecorder.ui.settings.SettingsStorageScreen
import io.github.mvolkert.entryrecorder.ui.settings.SettingsViewModel
import io.github.mvolkert.entryrecorder.ui.theme.motionScheme

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

// Editing a device is a settings-detail fullscreen route, reached from the Devices submenu.
private const val ROUTE_DEVICE_EDIT = "settings/device/{deviceId}"
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
    // The bar/rail is chrome for the three tabs only; every settings submenu and the device form are
    // fullscreen details that own their top bar, so chrome is hidden for anything non-top-level.
    val showChrome = currentDestination == null ||
        TopLevelScreens.any { it.route == currentDestination?.route }
    val items = TopLevelScreens.map { AdaptiveNavItem(it.route, it.labelRes, it.icon) }
    // NavHost transition lambdas are not @Composable, so resolve the seam once here and capture it.
    val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val tabScaleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    // Tabs are siblings, so their transition is symmetric (no shared axis): fade on the effects
    // slot, scale on the spatial one. These are graph-level NavHost defaults, not per-destination
    // params: with popUpTo(saveState)/restoreState tab switches, destination-level transitions can
    // be skipped by the library, and the detail routes below keep overriding with their own slides.
    val tabEnter: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(animationSpec = fadeSpec) +
            scaleIn(animationSpec = tabScaleSpec, initialScale = 0.92f)
    }
    val tabExit: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(animationSpec = fadeSpec) +
            scaleOut(animationSpec = tabScaleSpec, targetScale = 0.92f)
    }
    // One shared-axis X transition reused by every settings detail push (submenus + device form).
    val detailEnter: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(animationSpec = slideSpec, initialOffsetX = { it / 4 }) +
            fadeIn(animationSpec = fadeSpec)
    }
    val detailExit: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(animationSpec = slideSpec, targetOffsetX = { -it / 4 }) +
            fadeOut(animationSpec = fadeSpec)
    }
    val detailPopEnter: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(animationSpec = slideSpec, initialOffsetX = { -it / 4 }) +
            fadeIn(animationSpec = fadeSpec)
    }
    val detailPopExit: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(animationSpec = slideSpec, targetOffsetX = { it / 4 }) +
            fadeOut(animationSpec = fadeSpec)
    }

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
                enterTransition = tabEnter,
                exitTransition = tabExit,
                popEnterTransition = tabEnter,
                popExitTransition = tabExit,
            ) {
                composable(Screen.Live.route) {
                    Box(Modifier.appContentMaxWidth()) {
                        LiveCamerasScreen()
                    }
                }
                composable(Screen.Recordings.route) {
                    Box(Modifier.appContentMaxWidth()) {
                        RecordingsScreen()
                    }
                }
                composable(Screen.Settings.route) {
                    Box(Modifier.appContentMaxWidth()) {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onNavigate = { route -> navController.navigate(route) },
                        )
                    }
                }
                composable(
                    route = ROUTE_SETTINGS_DEVICES,
                    enterTransition = detailEnter,
                    exitTransition = detailExit,
                    popEnterTransition = detailPopEnter,
                    popExitTransition = detailPopExit,
                ) {
                    Box(Modifier.appContentMaxWidth()) {
                        SettingsDevicesScreen(
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() },
                            onEditDevice = { id -> navController.navigate("settings/device/$id") },
                            onAddDevice = { navController.navigate("settings/device/$NEW_DEVICE_ID") },
                        )
                    }
                }
                composable(
                    route = ROUTE_SETTINGS_ENGINE,
                    enterTransition = detailEnter,
                    exitTransition = detailExit,
                    popEnterTransition = detailPopEnter,
                    popExitTransition = detailPopExit,
                ) {
                    Box(Modifier.appContentMaxWidth()) {
                        SettingsEngineScreen(
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(
                    route = ROUTE_SETTINGS_STORAGE,
                    enterTransition = detailEnter,
                    exitTransition = detailExit,
                    popEnterTransition = detailPopEnter,
                    popExitTransition = detailPopExit,
                ) {
                    Box(Modifier.appContentMaxWidth()) {
                        SettingsStorageScreen(
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(
                    route = ROUTE_SETTINGS_APPEARANCE,
                    enterTransition = detailEnter,
                    exitTransition = detailExit,
                    popEnterTransition = detailPopEnter,
                    popExitTransition = detailPopExit,
                ) {
                    Box(Modifier.appContentMaxWidth()) {
                        SettingsAppearanceScreen(
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(
                    route = ROUTE_SETTINGS_BACKUP,
                    enterTransition = detailEnter,
                    exitTransition = detailExit,
                    popEnterTransition = detailPopEnter,
                    popExitTransition = detailPopExit,
                ) {
                    Box(Modifier.appContentMaxWidth()) {
                        SettingsBackupScreen(
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(
                    route = ROUTE_DEVICE_EDIT,
                    arguments = listOf(navArgument(ARG_DEVICE_ID) { type = NavType.LongType }),
                    enterTransition = detailEnter,
                    exitTransition = detailExit,
                    popEnterTransition = detailPopEnter,
                    popExitTransition = detailPopExit,
                ) { entry ->
                    val deviceId = entry.arguments?.getLong(ARG_DEVICE_ID) ?: NEW_DEVICE_ID
                    Box(Modifier.appContentMaxWidth()) {
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
