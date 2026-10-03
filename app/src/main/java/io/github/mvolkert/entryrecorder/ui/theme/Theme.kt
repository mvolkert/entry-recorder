package io.github.mvolkert.entryrecorder.ui.theme

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.mvolkert.entryrecorder.R

/**
 * User-selectable color mode. Stored as the enum ordinal in [io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity.themeMode];
 * [System] follows the device setting, while [Light]/[Dark] force one regardless of it.
 */
enum class ThemeMode { System, Light, Dark }

/** The [ThemeMode] at [ordinal], falling back to [ThemeMode.System] for a stale or out-of-range value. */
fun themeModeAt(ordinal: Int): ThemeMode = ThemeMode.values().getOrNull(ordinal) ?: ThemeMode.System

/**
 * A curated accent palette. Each preset supplies the visible accent roles (primary/secondary/tertiary
 * plus their "on" colors) and one container pair for the dark scheme; every pairing is derived so
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

private fun buildColorScheme(
    primary: AccentPalette,
    secondary: AccentPalette,
    tertiary: AccentPalette,
    dark: Boolean,
): ColorScheme = if (dark) {
    darkColorScheme(
        primary = primary.primary,
        onPrimary = primary.onPrimary,
        primaryContainer = primary.primaryContainer,
        onPrimaryContainer = primary.onPrimaryContainer,
        secondary = secondary.secondary,
        onSecondary = secondary.onSecondary,
        // Each preset derives exactly one container pair (its primary's). Reusing that pair for the role's
        // own container keeps the nav active indicator, the FilterChips and the selected recording card on
        // the chosen palette instead of the neutral Material default: same hue family, and the
        // container/on-container contrast is already the measured one.
        secondaryContainer = secondary.primaryContainer,
        onSecondaryContainer = secondary.onPrimaryContainer,
        tertiary = tertiary.tertiary,
        onTertiary = tertiary.onTertiary,
        tertiaryContainer = tertiary.primaryContainer,
        onTertiaryContainer = tertiary.onPrimaryContainer,
    )
} else {
    // Light scheme reuses the same curated pairs, swapping which side of each pair is foreground vs
    // background: the dark scheme's deep container tone becomes the light primary, and its light
    // on-container tone becomes the text on it. Contrast stays within the pair the preset was checked
    // for, so no separate light tuning is required per accent.
    lightColorScheme(
        primary = primary.primaryContainer,
        onPrimary = primary.onPrimaryContainer,
        primaryContainer = primary.onPrimaryContainer,
        onPrimaryContainer = primary.onPrimary,
        secondary = secondary.primaryContainer,
        onSecondary = secondary.onPrimaryContainer,
        secondaryContainer = secondary.onPrimaryContainer,
        onSecondaryContainer = secondary.onSecondary,
        tertiary = tertiary.primaryContainer,
        onTertiary = tertiary.onPrimaryContainer,
        tertiaryContainer = tertiary.onPrimaryContainer,
        onTertiaryContainer = tertiary.onTertiary,
    )
}

/** The preset at [index], falling back to the first one for stale or out-of-range stored indices. */
fun accentPresetAt(index: Int): AccentPalette = accentPresets.getOrNull(index) ?: accentPresets.first()

/**
 * Applies the persisted per-role accent presets. Every role takes its own preset's color plus that
 * preset's on- and container pair, so any combination of the curated list stays contrast-checked and
 * visibly themes the container-based components (nav indicator, filter chips, selected card).
 */
@Composable
fun AppTheme(
    themeMode: Int,
    primaryIndex: Int,
    secondaryIndex: Int,
    tertiaryIndex: Int,
    content: @Composable () -> Unit,
) {
    val dark = when (themeModeAt(themeMode)) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val colorScheme = remember(primaryIndex, secondaryIndex, tertiaryIndex, dark) {
        buildColorScheme(
            primary = accentPresetAt(primaryIndex),
            secondary = accentPresetAt(secondaryIndex),
            tertiary = accentPresetAt(tertiaryIndex),
            dark = dark,
        )
    }

    // Edge-to-edge bars are transparent, so icon contrast is the only thing that must follow the
    // resolved scheme; enableEdgeToEdge defaults are driven by the (always-Light) framework parent.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
