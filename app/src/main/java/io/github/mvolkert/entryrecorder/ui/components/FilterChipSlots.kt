package io.github.mvolkert.entryrecorder.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The leading slot of a filter chip, the same for every chip set in the app: the tick marks the chosen
 * chip, an unchosen one may carry its category glyph.
 *
 * A glyph that sits inside the label instead is a font glyph, not a component slot — no theme colour
 * reaches it, the chip's fill cannot pair with it, and a screen reader reads it aloud as part of the
 * name. That is why the emoji that used to prefix these labels are gone.
 *
 * [glyph] is null where a chip has no category to show, which leaves the plain selected → tick,
 * unselected → nothing rule.
 */
@Composable
fun filterChipLeadingIcon(selected: Boolean, glyph: ImageVector? = null): (@Composable () -> Unit)? =
    when {
        selected -> { { Icon(Icons.Default.Check, contentDescription = null) } }
        glyph != null -> { { Icon(glyph, contentDescription = null) } }
        else -> null
    }
