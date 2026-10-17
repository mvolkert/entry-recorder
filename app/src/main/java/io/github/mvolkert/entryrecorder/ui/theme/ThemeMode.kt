package io.github.mvolkert.entryrecorder.ui.theme

/**
 * User-selectable color mode. Stored as the enum ordinal in [io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity.themeMode];
 * [System] follows the device setting, while [Light]/[Dark] force one regardless of it.
 * MainActivity additionally pins the framework night mode to this choice, so the system splash
 * and window background resolve the matching day/night resources.
 */
enum class ThemeMode { System, Light, Dark }

/** The [ThemeMode] at [ordinal], falling back to [ThemeMode.System] for a stale or out-of-range value. */
fun themeModeAt(ordinal: Int): ThemeMode = ThemeMode.values().getOrNull(ordinal) ?: ThemeMode.System
