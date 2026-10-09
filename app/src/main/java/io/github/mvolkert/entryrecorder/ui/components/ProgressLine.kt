package io.github.mvolkert.entryrecorder.ui.components

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.mvolkert.entryrecorder.ui.theme.rememberExpressiveMotionEnabled

/**
 * Determinate linear progress for the long task dialogs. Playful tier shows the wavy bar; with the
 * system animation scale at zero it drops to the flat indicator, per Design.md §3. One wrapper keeps
 * the animations-off rule out of every dialog.
 */
@Composable
fun ProgressLine(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    if (rememberExpressiveMotionEnabled()) {
        LinearWavyProgressIndicator(
            progress = { progress },
            modifier = modifier,
            color = color,
            trackColor = trackColor,
        )
    } else {
        LinearProgressIndicator(
            progress = { progress },
            modifier = modifier,
            color = color,
            trackColor = trackColor,
        )
    }
}
