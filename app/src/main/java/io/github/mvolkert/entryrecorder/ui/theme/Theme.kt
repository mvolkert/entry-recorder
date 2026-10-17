package io.github.mvolkert.entryrecorder.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** The resolved dark/light decision of the enclosing [AppTheme], for widgets that theme themselves per mode. */
val LocalDarkTheme = staticCompositionLocalOf { true }

/**
 * Applies the persisted per-role accent presets. Every role takes its own preset's color plus that
 * preset's on- and container pair, so any combination of the curated list stays contrast-checked and
 * visibly themes the container-based components (nav indicator, filter chips, selected card).
 *
 * When [useDynamicColor] is true and the platform supports it (Android 12+/API 31+), the curated
 * presets are bypassed and the whole scheme comes from `dynamic{Light,Dark}ColorScheme(context)`.
 * Older API levels silently fall through to the curated accents so the setting is inert instead of
 * crashing.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppTheme(
    themeMode: Int,
    primaryIndex: Int,
    secondaryIndex: Int,
    tertiaryIndex: Int,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeModeAt(themeMode)) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val dynamicAvailable = useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = if (dynamicAvailable) {
        remember(dark) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
    } else {
        remember(primaryIndex, secondaryIndex, tertiaryIndex, dark) {
            buildColorScheme(
                primary = accentPresetAt(primaryIndex),
                secondary = accentPresetAt(secondaryIndex),
                tertiary = accentPresetAt(tertiaryIndex),
                dark = dark,
            )
        }
    }

    // Edge-to-edge bars are transparent, so icon contrast is the only thing that must follow the
    // resolved scheme; MainActivity's uiMode override keeps the framework side in agreement.
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

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
    ) {
        CompositionLocalProvider(
            LocalDarkTheme provides dark,
            content = content,
        )
    }
}
