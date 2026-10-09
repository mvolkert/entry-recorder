package io.github.mvolkert.entryrecorder.ui.components

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
        CircularProgressIndicator(modifier = modifier, color = color)
    }
}
