package io.github.mvolkert.entryrecorder.ui.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.mvolkert.entryrecorder.R

/**
 * A curated accent palette. Each preset supplies the visible accent roles (primary/secondary/
 * tertiary plus their "on" and container colors) for the dark scheme; every pairing is chosen so
 * foreground/background contrast stays readable, so users cannot pick a broken combination the way
 * a free color wheel would allow.
 */
data class AccentPalette(
    @StringRes val labelRes: Int,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val tertiary: Color,
    val onTertiary: Color,
)

// index 0 mirrors the Material 3 baseline dark scheme so the default stays identical to the
// previous single-palette look.
val accentPresets: List<AccentPalette> = listOf(
    AccentPalette(
        labelRes = R.string.accent_default,
        primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
        primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
        secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
        tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
    ),
    AccentPalette(
        labelRes = R.string.accent_teal,
        primary = Color(0xFF6DDDE6), onPrimary = Color(0xFF00363D),
        primaryContainer = Color(0xFF004F59), onPrimaryContainer = Color(0xFF9CEEF6),
        secondary = Color(0xFFB0C9CB), onSecondary = Color(0xFF1B3537),
        tertiary = Color(0xFFAEC0E6), onTertiary = Color(0xFF1A2E4A),
    ),
    AccentPalette(
        labelRes = R.string.accent_amber,
        primary = Color(0xFFF2C14E), onPrimary = Color(0xFF402D00),
        primaryContainer = Color(0xFF5B4200), onPrimaryContainer = Color(0xFFFFDF9E),
        secondary = Color(0xFFD7C08A), onSecondary = Color(0xFF3B2E0B),
        tertiary = Color(0xFFBAA4C7), onTertiary = Color(0xFF2A1B37),
    ),
    AccentPalette(
        labelRes = R.string.accent_magenta,
        primary = Color(0xFFFFA9D4), onPrimary = Color(0xFF4E003A),
        primaryContainer = Color(0xFF6F2A57), onPrimaryContainer = Color(0xFFFFD5E8),
        secondary = Color(0xFFE7BACB), onSecondary = Color(0xFF3F2932),
        tertiary = Color(0xFFD8C29A), onTertiary = Color(0xFF3A2D13),
    ),
    AccentPalette(
        labelRes = R.string.accent_blue,
        primary = Color(0xFFA9C7FF), onPrimary = Color(0xFF002F63),
        primaryContainer = Color(0xFF00448C), onPrimaryContainer = Color(0xFFD6E3FF),
        secondary = Color(0xFFB7C9E0), onSecondary = Color(0xFF213244),
        tertiary = Color(0xFFD0BCEC), onTertiary = Color(0xFF372750),
    ),
    AccentPalette(
        labelRes = R.string.accent_green,
        primary = Color(0xFF9DDC8B), onPrimary = Color(0xFF0A3D10),
        primaryContainer = Color(0xFF215A28), onPrimaryContainer = Color(0xFFBDF6A6),
        secondary = Color(0xFFB6CCAB), onSecondary = Color(0xFF22351F),
        tertiary = Color(0xFFA6CBCE), onTertiary = Color(0xFF0E373B),
    ),
    AccentPalette(
        labelRes = R.string.accent_coral,
        primary = Color(0xFFFFB4A9), onPrimary = Color(0xFF682412),
        primaryContainer = Color(0xFF843628), onPrimaryContainer = Color(0xFFFFDBD3),
        secondary = Color(0xFFE7BDB6), onSecondary = Color(0xFF482824),
        tertiary = Color(0xFFFFB3CF), onTertiary = Color(0xFF561436),
    ),
    AccentPalette(
        labelRes = R.string.accent_violet,
        primary = Color(0xFFC2B8FF), onPrimary = Color(0xFF2A1492),
        primaryContainer = Color(0xFF4129AE), onPrimaryContainer = Color(0xFFE4DDFF),
        secondary = Color(0xFFC6C0DD), onSecondary = Color(0xFF2F2B44),
        tertiary = Color(0xFFE3B7D3), onTertiary = Color(0xFF452346),
    ),
)

private fun AccentPalette.toColorScheme(): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onSecondary,
    tertiary = tertiary,
    onTertiary = onTertiary,
)

/** Applies the persisted accent preset [index] (falling back to the baseline palette when unset). */
@Composable
fun AppTheme(
    accentIndex: Int,
    content: @Composable () -> Unit,
) {
    val palette = accentPresets.getOrNull(accentIndex) ?: accentPresets.first()
    MaterialTheme(
        colorScheme = palette.toColorScheme(),
        content = content,
    )
}
