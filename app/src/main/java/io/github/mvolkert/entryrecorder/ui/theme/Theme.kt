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
 * tertiary plus their "on" and container colors) for the dark scheme; every pairing is derived so
 * foreground/background contrast stays at or above WCAG AA (4.5:1), so users cannot pick a broken
 * combination the way a free color wheel would allow.
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

// Chroma-first presets, derived in OKLCh (not picked by eye): each primary and primaryContainer takes
// ~90% of the chroma the sRGB gamut allows at its own lightness, on the **same hue** as the previous
// baseline set — the old values were Material 3 tone-80 presets, which are low-chroma by construction.
// Lightness was traded for chroma: primaries sit at OKLCh L≈0.76 so they still clear 7:1 against the
// dark surface (`primary` doubles as the text/icon color of section headers, buttons, switches), and
// every on*/background pair stays at or above 5.07:1. Do not hand-edit a single role: re-derive the
// pair, otherwise the accent silently becomes unreadable at one size or the other.
val accentPresets: List<AccentPalette> = listOf(
    AccentPalette(
        labelRes = R.string.accent_default,
        primary = Color(0xFFBB9FF8), onPrimary = Color(0xFF2B1E42),
        primaryContainer = Color(0xFF7322CC), onPrimaryContainer = Color(0xFFE8E6EF),
        secondary = Color(0xFFC1ACE0), onSecondary = Color(0xFF2D1E40),
        tertiary = Color(0xFFEF9BB5), onTertiary = Color(0xFF3A1B26),
    ),
    AccentPalette(
        labelRes = R.string.accent_teal,
        primary = Color(0xFF39C7D1), onPrimary = Color(0xFF1A2A2B),
        primaryContainer = Color(0xFF1A6A70), onPrimaryContainer = Color(0xFFD6EDEF),
        secondary = Color(0xFF7DC6CC), onSecondary = Color(0xFF1A2A2B),
        tertiary = Color(0xFF9EB7EE), onTertiary = Color(0xFF12234F),
    ),
    AccentPalette(
        labelRes = R.string.accent_amber,
        primary = Color(0xFFD9A932), onPrimary = Color(0xFF2C2618),
        primaryContainer = Color(0xFF745916), onPrimaryContainer = Color(0xFFEFE7D7),
        secondary = Color(0xFFCFB475), onSecondary = Color(0xFF2C2618),
        tertiary = Color(0xFFD2A1EE), onTertiary = Color(0xFF311D3B),
    ),
    AccentPalette(
        labelRes = R.string.accent_magenta,
        primary = Color(0xFFF884C2), onPrimary = Color(0xFF381B2B),
        primaryContainer = Color(0xFFA01C6E), onPrimaryContainer = Color(0xFFEFE5E9),
        secondary = Color(0xFFE2A2BB), onSecondary = Color(0xFF391B28),
        tertiary = Color(0xFFDDB055), onTertiary = Color(0xFF2D2518),
    ),
    AccentPalette(
        labelRes = R.string.accent_blue,
        primary = Color(0xFF8BB1F7), onPrimary = Color(0xFF162543),
        primaryContainer = Color(0xFF114ED2), onPrimaryContainer = Color(0xFFE4E8EF),
        secondary = Color(0xFF9DBADF), onSecondary = Color(0xFF192739),
        tertiary = Color(0xFFC6A7EE), onTertiary = Color(0xFF2D1D3F),
    ),
    AccentPalette(
        labelRes = R.string.accent_green,
        primary = Color(0xFF5ECF33), onPrimary = Color(0xFF1D2B19),
        primaryContainer = Color(0xFF2F6F17), onPrimaryContainer = Color(0xFFDAEFD4),
        secondary = Color(0xFF94C976), onSecondary = Color(0xFF1F2A19),
        tertiary = Color(0xFF5ECAD3), onTertiary = Color(0xFF1A2A2B),
    ),
    AccentPalette(
        labelRes = R.string.accent_coral,
        primary = Color(0xFFF89082), onPrimary = Color(0xFF3B1C18),
        primaryContainer = Color(0xFFAB1E19), onPrimaryContainer = Color(0xFFEFE5E4),
        secondary = Color(0xFFE1A79D), onSecondary = Color(0xFF3B1C18),
        tertiary = Color(0xFFEF9ABA), onTertiary = Color(0xFF391B27),
    ),
    AccentPalette(
        labelRes = R.string.accent_violet,
        primary = Color(0xFFB1A3F8), onPrimary = Color(0xFF271F45),
        primaryContainer = Color(0xFF6624D9), onPrimaryContainer = Color(0xFFE7E7EF),
        secondary = Color(0xFFBAAFE0), onSecondary = Color(0xFF291E43),
        tertiary = Color(0xFFEF95D2), onTertiary = Color(0xFF371B2E),
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

/** Applies the persisted accent preset index (falling back to the first preset when unset). */
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
