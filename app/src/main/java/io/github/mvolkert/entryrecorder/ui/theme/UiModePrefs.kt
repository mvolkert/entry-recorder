package io.github.mvolkert.entryrecorder.ui.theme

import android.content.Context
import androidx.core.content.edit

/**
 * Synchronous mirror of [io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity.themeMode]
 * (a [ThemeMode] ordinal). Room cannot be read during `attachBaseContext`, but the window theme must
 * already resolve the correct day/night resources there so the splash matches the picker on a cold
 * start. [io.github.mvolkert.entryrecorder.ui.settings.SettingsViewModel] writes here on every save;
 * MainActivity reads here before the window inflates its theme.
 */
object UiModePrefs {
    private const val PREFS = "ui_mode"
    private const val KEY_THEME_MODE = "themeMode"

    fun themeMode(context: Context): Int = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_THEME_MODE, ThemeMode.System.ordinal)

    fun setThemeMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putInt(KEY_THEME_MODE, mode) }
    }
}
