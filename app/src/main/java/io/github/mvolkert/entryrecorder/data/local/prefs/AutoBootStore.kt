package io.github.mvolkert.entryrecorder.data.local.prefs

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Synchronous mirror of [io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity.autoBootEnabled].
 *
 * [io.github.mvolkert.entryrecorder.receiver.BootReceiver] runs on the main thread the instant the device
 * finishes booting, before the Hilt graph or Room are warm; opening the database to decide whether to
 * autostart would block that broadcast and risk the FGS never starting. So the flag is also written here
 * (the same mirror pattern as [io.github.mvolkert.entryrecorder.ui.theme.UiModePrefs]) and the receiver
 * reads it without a coroutine. [io.github.mvolkert.entryrecorder.ui.settings.SettingsViewModel] keeps the
 * two in sync on every settings save.
 *
 * Defaults to enabled so an install upgrading from before this setting exists keeps the autostart it
 * already had — matching the `autoBootEnabled` column's migration default — until the user turns it off.
 */
object AutoBootPrefs {
    private const val PREFS = "boot_gate"
    private const val KEY_AUTO_BOOT = "autoBootEnabled"

    fun isEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_AUTO_BOOT, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_AUTO_BOOT, enabled) }
    }
}

/**
 * The synchronous write side of [AutoBootPrefs] as a JVM-only contract, so the settings ViewModel can mirror
 * the flag on save without holding a `Context` — see [io.github.mvolkert.entryrecorder.ui.theme.ThemeModeStore]
 * for the same split.
 */
interface AutoBootStore {

    /** Stores whether the monitor service may autostart on boot. */
    fun setAutoBootEnabled(enabled: Boolean)
}

/** The device-backed implementation: SharedPreferences through the application context. */
class AndroidAutoBootStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : AutoBootStore {
    override fun setAutoBootEnabled(enabled: Boolean) = AutoBootPrefs.setEnabled(context, enabled)
}
