package io.github.mvolkert.entryrecorder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tonal pill for live status: a filled container with its matched on-color label, optionally led by a
 * colored dot. Single shared shape (stadium) and type role so every status surface in the app reads as the
 * same family; callers pass contrast-checked container/on pairs from the theme's status palette, never raw
 * hues.
 *
 * [compact] is the same badge at its tightest setting, for a pill that has to fit beside an existing text
 * line without making the row taller — the live REC badge on a device card. Its interior numbers stay raw
 * on purpose: they are metrics of the pill, not gaps between components (see
 * [io.github.mvolkert.entryrecorder.ui.theme.Spacing]).
 */
@Composable
fun StatusChip(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    leadingDotColor: Color? = null,
    compact: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape, // beta01 Shapes has no `full` token; CircleShape renders the stadium pill on wide boxes
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 5.dp else 8.dp,
                vertical = if (compact) 1.dp else 2.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
        ) {
            if (leadingDotColor != null) {
                Box(
                    modifier = Modifier
                        .size(if (compact) 5.dp else 6.dp)
                        .background(leadingDotColor, CircleShape)
                )
            }
            Text(
                text = label,
                // The compact form keeps the emphasized small role, which is the lightest type step that
                // still reads as a badge label rather than as caption text under the dot.
                style = if (compact) {
                    MaterialTheme.typography.labelSmallEmphasized
                } else {
                    MaterialTheme.typography.labelMedium
                },
                maxLines = 1,
            )
        }
    }
}
