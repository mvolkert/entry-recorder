package io.github.mvolkert.entryrecorder.ui.adaptive

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.mvolkert.entryrecorder.ui.theme.Spacing

/**
 * One top-level navigation entry. Kept independent of the `Screen` sealed class so the adaptive
 * container does not depend on the ui package.
 */
data class AdaptiveNavItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

/**
 * Bar/rail container shared by every top-level destination. Compact renders a bottom [NavigationBar]
 * inside a [Scaffold]; Medium and Expanded render a leading [NavigationRail] with the content on the
 * remaining width. When [showChrome] is false (e.g. a fullscreen sibling route such as device edit or any
 * settings submenu) neither bar is drawn and the content fills the window.
 *
 * The window size class alone picks the layout and [content] is invoked from exactly one call site per
 * class, with [showChrome] only deciding whether the bar composes. Moving the `content(...)` call into a
 * separate branch would recompose the whole destination subtree from scratch — which the screens below
 * cannot survive, because they hold their own state in `remember` (pushing a settings submenu flips the
 * chrome, and a rebuilt destination would lose whatever that screen was mid-way through).
 *
 * [snackbarHost] is drawn here and nowhere else, so it is the one host every screen under the graph
 * posts to (see [io.github.mvolkert.entryrecorder.ui.LocalAppSnackbar]). The compact layout hands it to
 * [Scaffold], which parks it above the bottom bar; the rail layout has no [Scaffold] to ask, so the host
 * is bottom-aligned over the content column and clears the navigation bar itself.
 */
@Composable
fun AdaptiveScaffold(
    modifier: Modifier = Modifier,
    items: List<AdaptiveNavItem>,
    selectedRoute: String?,
    showChrome: Boolean,
    snackbarHost: SnackbarHostState,
    onNavigate: (AdaptiveNavItem) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val info = LocalWindowInfo.current
    if (info.useRail) {
        Row(modifier.fillMaxSize()) {
            if (showChrome) {
                NavigationRail {
                    items.forEach { item ->
                        val selected = item.route == selectedRoute
                        NavigationRailItem(
                            selected = selected,
                            onClick = { onNavigate(item) },
                            icon = { AdaptiveIcon(item = item, selected = selected) },
                            label = { Text(stringResource(item.labelRes)) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                content(Modifier)
                SnackbarHost(
                    hostState = snackbarHost,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(Spacing.lg),
                )
            }
        }
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0),
            // Only the side margins are added by hand; the Scaffold owns the vertical placement.
            snackbarHost = { SnackbarHost(snackbarHost, Modifier.padding(horizontal = Spacing.lg)) },
            bottomBar = {
                if (showChrome) {
                    NavigationBar {
                        items.forEach { item ->
                            val selected = item.route == selectedRoute
                            NavigationBarItem(
                                selected = selected,
                                onClick = { onNavigate(item) },
                                icon = { AdaptiveIcon(item = item, selected = selected) },
                                label = { Text(stringResource(item.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            // An empty bottom bar measures 0, so hidden chrome leaves innerPadding empty and the content
            // gets the whole window — identical to the pre-refactor branch that skipped the Scaffold.
            Box(Modifier.fillMaxSize()) {
                content(Modifier.padding(innerPadding))
            }
        }
    }
}

/** Selected-item icon spring shared by bar and rail; matches the Phase B `slowSpatialSpec` look. */
@Composable
private fun AdaptiveIcon(item: AdaptiveNavItem, selected: Boolean) {
    val label = stringResource(item.labelRes)
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "adaptiveNavIconScale",
    )
    Icon(
        item.icon,
        contentDescription = label,
        modifier = Modifier.scale(iconScale),
    )
}
