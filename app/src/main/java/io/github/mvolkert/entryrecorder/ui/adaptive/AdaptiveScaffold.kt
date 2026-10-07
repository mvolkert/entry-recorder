package io.github.mvolkert.entryrecorder.ui.adaptive

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.mvolkert.entryrecorder.ui.theme.motionScheme

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
 * remaining width. When [showChrome] is false (e.g. a fullscreen sibling route such as device edit)
 * neither container is drawn and the content fills the window.
 */
@Composable
fun AdaptiveScaffold(
    items: List<AdaptiveNavItem>,
    selectedRoute: String?,
    showChrome: Boolean,
    onNavigate: (AdaptiveNavItem) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val info = LocalWindowInfo.current
    when {
        !showChrome -> Box(Modifier.fillMaxSize()) { content(Modifier) }
        info.useRail -> Row(Modifier.fillMaxSize()) {
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
            Box(Modifier.weight(1f).fillMaxSize()) { content(Modifier) }
        }
        else -> Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
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
            },
        ) { innerPadding ->
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
