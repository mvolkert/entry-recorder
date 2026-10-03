package io.github.mvolkert.entryrecorder.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.github.mvolkert.entryrecorder.R
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Enforces the "every curated pairing is contrast-checked" claim: for all 8 presets in both
 * color modes, every foreground/background role pair used together must clear WCAG AA (4.5:1),
 * and the primary — which doubles as text/icon tint on the plain surface — must clear it there too.
 * If re-deriving via tools/derive_accents.py regresses a pair, this test fails before it ships.
 */
class AccentPaletteContrastTest {

    @Test
    fun `all accent role pairs clear AA in dark mode`() {
        for (palette in accentPresets) {
            val scheme = buildColorScheme(palette, palette, palette, dark = true)
            assertPairs(palette.labelName + ".dark", scheme)
        }
    }

    @Test
    fun `all accent role pairs clear AA in light mode`() {
        for (palette in accentPresets) {
            val scheme = buildColorScheme(palette, palette, palette, dark = false)
            assertPairs(palette.labelName + ".light", scheme)
        }
    }

    private fun assertPairs(label: String, scheme: ColorScheme) {
        assertAtLeast("$label primary/onPrimary", scheme.onPrimary, scheme.primary)
        assertAtLeast("$label container", scheme.onPrimaryContainer, scheme.primaryContainer)
        assertAtLeast("$label secondary/onSecondary", scheme.onSecondary, scheme.secondary)
        assertAtLeast("$label secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer)
        assertAtLeast("$label tertiary/onTertiary", scheme.onTertiary, scheme.tertiary)
        assertAtLeast("$label tertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer)
        // primary doubles as the text/icon tint of headers, buttons and switches on plain surfaces
        assertAtLeast("$label primary-on-surface", scheme.primary, scheme.surface)
        assertAtLeast("$label onSurface/surface", scheme.onSurface, scheme.surface)
        assertAtLeast("$label onSurfaceVariant/surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant)
        assertAtLeast("$label onBackground/background", scheme.onBackground, scheme.background)
    }

    private fun assertAtLeast(name: String, fg: Color, bg: Color) {
        val ratio = contrast(fg, bg)
        assertTrue("$name contrast ${"%.2f".format(ratio)} < 4.5", ratio >= 4.5)
    }

    private fun contrast(fg: Color, bg: Color): Double {
        val f = luminance(fg)
        val g = luminance(bg)
        val hi = maxOf(f, g)
        val lo = minOf(f, g)
        return (hi + 0.05) / (lo + 0.05)
    }

    // WCAG relative luminance from the (gamma-encoded) sRGB channels of the color.
    private fun luminance(color: Color): Double {
        fun linear(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }

    private val AccentPalette.labelName: String
        get() = when (labelRes) {
            R.string.accent_default -> "default"
            R.string.accent_teal -> "teal"
            R.string.accent_amber -> "amber"
            R.string.accent_magenta -> "magenta"
            R.string.accent_blue -> "blue"
            R.string.accent_green -> "green"
            R.string.accent_coral -> "coral"
            else -> "violet"
        }
}
