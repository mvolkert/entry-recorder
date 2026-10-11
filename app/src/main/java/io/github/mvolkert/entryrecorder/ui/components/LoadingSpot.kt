package io.github.mvolkert.entryrecorder.ui.components

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import io.github.mvolkert.entryrecorder.ui.theme.rememberExpressiveMotionEnabled

/**
 * The app's single screen-level loader: the expressive shape-morphing [LoadingIndicator] instead of a
 * plain circular spinner. The experimental opt-in stays scoped here so no screen has to carry it, and
 * with the system animation scale at zero it falls back to the flat spinner (Design.md §3).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingSpot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (rememberExpressiveMotionEnabled()) {
        LoadingIndicator(modifier = modifier, color = color)
    } else {
        LoadingSpinner(modifier = modifier, color = color)
    }
}

/**
 * The app's one circular spinner for the waits that live inside another component — a video surface, a
 * button's own progress, a row's trailing state. Indeterminate, and either the expressive wavy circle or
 * the flat one, decided here the way [ProgressLine] decides its bars, so no call site has to opt into the
 * expressive API or branch on the animation scale itself (Design.md §3).
 *
 * [strokeWidth] is for the dense sizes, where Material's 4 dp default would fill the whole glyph. Pass
 * null to take each component's own default.
 */
@Composable
fun LoadingSpinner(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp? = null,
) {
    if (rememberExpressiveMotionEnabled()) {
        val stroke = strokeWidth?.let { pixelStroke(it) }
        CircularWavyProgressIndicator(
            modifier = modifier,
            color = color,
            stroke = stroke ?: WavyProgressIndicatorDefaults.circularIndicatorStroke,
            trackStroke = stroke ?: WavyProgressIndicatorDefaults.circularTrackStroke,
        )
    } else {
        CircularProgressIndicator(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth ?: ProgressIndicatorDefaults.CircularStrokeWidth,
        )
    }
}

/**
 * The wavy circle has no `strokeWidth` parameter: its width arrives as a [Stroke] measured in pixels, so
 * the same [Dp] has to be resolved against the local density. The round cap is what the component's own
 * defaults use, which keeps a thinned wave reading as the same glyph.
 */
@Composable
private fun pixelStroke(width: Dp): Stroke =
    Stroke(width = with(LocalDensity.current) { width.toPx() }, cap = StrokeCap.Round)
