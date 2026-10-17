package io.github.mvolkert.entryrecorder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The REC red for a glyph drawn directly on the resting record FAB's neutral container, one step lighter in
 * dark than [recordingStatusColor]. A FAB glyph is a non-text element, so the bar is 3:1, and the single
 * darker red only clears it on the light container (about 4.7:1) — on the dark one it lands under 3:1.
 * Kept in the same hue family so the control reads as the same signal in both modes.
 */
fun recordingGlyphColorFor(dark: Boolean): Color =
    if (dark) StatusPalette.recordingGlyphDark else StatusPalette.recordingGlyphLight

/** [recordingGlyphColorFor] for the mode the theme resolved to. */
@Composable
fun recordingGlyphColor(): Color = recordingGlyphColorFor(LocalDarkTheme.current)

/**
 * Material's disabled-content rule: `onSurface` at 38 %. Anything that has to read as inert — a glyph or a
 * dot that cannot be acted on — fades to this rather than to a hand-picked alpha on a variant text role,
 * so every "this is off" in the app is the same distance from its active form.
 */
const val DisabledContentAlpha = 0.38f

/** [DisabledContentAlpha] applied to the theme's `onSurface`. */
@Composable
fun disabledContentColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContentAlpha)

/** Opaque black that pillarboxes video whose aspect ratio differs from its container. */
val VideoScrim: Color = Color.Black

/** Foreground drawn on the dark video scrim / letterbox, provided once via LocalContentColor. */
val onScrimColor: Color = Color.White

/**
 * App spacing scale. Material 3 ships no spacing tokens, so layout gaps and container padding name a
 * step here rather than a bare dp value. The steps are chosen so the values already in the layouts map
 * one-to-one (xs 4, sm 8, md 12, lg 16, xl 24).
 *
 * Badge interiors are the named exception, and stay raw on purpose: the 4/6 dp and the compact 5/1/3 dp
 * inside [io.github.mvolkert.entryrecorder.ui.components.StatusChip] are
 * metrics of a pill that has to stay no taller than the text line beside it, not gaps between two
 * components. A step from this scale would round them up and reflow the row they exist to fit.
 */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
}
