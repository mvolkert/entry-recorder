package io.github.mvolkert.entryrecorder.ui.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The synchronous write side of [UiModePrefs], as a JVM-only contract: the settings ViewModel mirrors the
 * theme mode on every save (so the next cold start's `attachBaseContext` resolves the right day/night
 * resources) but must not hold a `Context` to do it — see [io.github.mvolkert.entryrecorder.ui.settings.SettingsViewModel].
 */
interface ThemeModeStore {

    /** Stores the color mode: the ordinal of [ThemeMode]. */
    fun setThemeMode(mode: Int)
}

/** The device-backed implementation: SharedPreferences through the application context. */
class AndroidThemeModeStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : ThemeModeStore {
    override fun setThemeMode(mode: Int) = UiModePrefs.setThemeMode(context, mode)
}
