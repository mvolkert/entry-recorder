package io.github.mvolkert.entryrecorder.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
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
import kotlinx.coroutines.launch

/**
 * A top-level destination reachable from the pager pages as well as the bottom bar (Compact) or the
 * rail (Medium+). The three are siblings inside a single [HorizontalPager] under [ROUTE_TABS], so
 * switching tabs is a swipe that keeps each screen's composition (scroll position, running feeds)
 * alive; only the settings detail routes get their own back stack slots.
 */
sealed class Screen(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    object Live : Screen("live", R.string.nav_live, Icons.Default.Videocam)
    object Recordings : Screen("recordings", R.string.nav_recordings, Icons.Default.VideoLibrary)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
}

private val TopLevelScreens = listOf(Screen.Live, Screen.Recordings, Screen.Settings)

// Single NavHost destination hosting the three swipeable tab pages.
private const val ROUTE_TABS = "tabs"

// Editing a device is a settings-detail fullscreen route, reached from the Devices submenu.
private const val ROUTE_DEVICE_EDIT = "settings/device/{deviceId}"
private const val ARG_DEVICE_ID = "deviceId"

/**
 * Root composable. Owns the [androidx.navigation.NavHostController] and the hoisted pager state (kept
 * above the NavHost so a settings-detail push does not reset the selected tab), hoists the adaptive
 * [io.github.mvolkert.entryrecorder.ui.adaptive.WindowInfo] into a [LocalWindowInfo] provider, and
 * wraps the NavHost inside an [AdaptiveScaffold] so the same three destinations render either under a
 * bottom [androidx.compose.material3.NavigationBar] or a leading [androidx.compose.material3.NavigationRail]
 * depending on window width.
 */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun AppRoot(settingsViewModel: SettingsViewModel) {
    val windowInfo = rememberWindowInfo()
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    // Hoisted above the NavHost: it is rememberSaveable internally, so it survives both the detail
    // pushes (whose composition tears the pager down) and configuration changes.
    val pagerState = rememberPagerState(pageCount = { TopLevelScreens.size })
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentEntry?.destination
    val onTabsRoute = currentDestination == null || currentDestination.route == ROUTE_TABS
    // The gallery player is an in-window overlay drawn inside the tab page, so it has to be handed the
    // whole window: while it is open the bar/rail is hidden exactly like a detail route hides it.
    // Hoisted here because the chrome belongs to this scaffold, not to the Recordings tab.
    var immersivePlayback by remember { mutableStateOf(false) }
    // The bar/rail is chrome for the three tabs only; every settings submenu and the device form are
    // fullscreen details that own their top bar, so chrome is hidden for anything non-top-level.
    val showChrome = onTabsRoute && !immersivePlayback
    val selectedRoute = if (onTabsRoute) TopLevelScreens[pagerState.currentPage].route else null
    val items = TopLevelScreens.map { AdaptiveNavItem(it.route, it.labelRes, it.icon) }
    // NavHost transition lambdas are not @Composable, so resolve the seam once here and capture it.
    val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
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
            onNavigate = { item ->
                // Only reachable while on the tabs route (chrome is hidden on details), so the pager
                // is attached and the animated scroll always drives the visible page.
                val index = TopLevelScreens.indexOfFirst { it.route == item.route }
                if (index >= 0) scope.launch { pagerState.animateScrollToPage(index) }
            },
        ) { contentModifier ->
            // One scope above the whole graph so a list card and the detail screen it opens share keyed bounds.
            SharedTransitionLayout {
            NavHost(
                navController = navController,
                startDestination = ROUTE_TABS,
                modifier = contentModifier.fillMaxSize(),
            ) {
                composable(
                    route = ROUTE_TABS,
                    enterTransition = { fadeIn(animationSpec = fadeSpec) },
                    exitTransition = detailExit,
                    popEnterTransition = detailPopEnter,
                    popExitTransition = detailExit,
                ) {
                    // All pages stay composed so each tab keeps its scroll and identity across tab
                    // switches; the Live page gates its streams on [currentPage] via `active`.
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = TopLevelScreens.size - 1,
                    ) { page ->
                        Box(Modifier.appContentMaxWidth()) {
                            when (TopLevelScreens[page]) {
                                Screen.Live -> LiveCamerasScreen(active = pagerState.currentPage == page)
                                Screen.Recordings -> RecordingsScreen(
                                    active = pagerState.currentPage == page,
                                    onFullscreenPlayback = { immersivePlayback = it },
                                )
                                Screen.Settings -> SettingsScreen(
                                    viewModel = settingsViewModel,
                                    onNavigate = { route -> navController.navigate(route) },
                                )
                            }
                        }
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
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@composable,
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
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@composable,
                        )
                    }
                }
            }
            }
        }
    }
}
