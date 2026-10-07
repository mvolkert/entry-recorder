package io.github.mvolkert.entryrecorder.ui.theme

import androidx.compose.ui.graphics.Color
import io.github.mvolkert.entryrecorder.data.model.EventType
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Guards the fixed status/event palette's contrast claim: every badge foreground/container pairing and
 * every event hue read as on-scrim header text must clear WCAG AA (4.5:1). The event colors are
 * deliberately mode-independent (they sit on the black video scrim regardless of the app theme), so this
 * checks each one against that scrim as well as against its own dark on-container. Re-tuning a hue in
 * Theme.kt without re-checking these ratios fails here before it ships.
 */
class StatusPaletteContrastTest {

    private val eventTypes = EventType.entries

    @Test
    fun `event badge fill and its on-container clear AA`() {
        for (type in eventTypes) {
            assertAtLeast("$type onContainer/container", eventTypeOnColor(type), eventTypeColor(type))
        }
    }

    @Test
    fun `event color reads on the video scrim`() {
        for (type in eventTypes) {
            assertAtLeast("$type on-scrim", eventTypeColor(type), VideoScrim)
        }
    }

    @Test
    fun `recording badge and its foreground clear AA`() {
        assertAtLeast("recording onContainer", onRecordingStatusColor, recordingStatusColor)
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
}
