package io.github.mvolkert.entryrecorder.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The fill of the band a title bar sits in, for the screens where the bar shares that fill with content
 * underneath it — the Recordings tab, where bar, search, storage line and filter rows are one surface.
 *
 * `surface` at rest and `surfaceContainer` once content has scrolled under the bar is exactly the pair
 * Material gives a [androidx.compose.material3.TopAppBar] through `containerColor` /
 * `scrolledContainerColor`, so the bar and the strip below it step together instead of showing a seam
 * between an animated bar and a static one. A bar that paints itself should keep its own defaults rather
 * than call this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeaderBandColor(state: TopAppBarState): Color {
    val scheme = MaterialTheme.colorScheme
    val target = if (state.overlappedFraction > 0.5f) scheme.surfaceContainer else scheme.surface
    return animateColorAsState(
        targetValue = target,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "headerBand",
    ).value
}
