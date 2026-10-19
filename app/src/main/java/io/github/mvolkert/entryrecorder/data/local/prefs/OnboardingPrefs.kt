package io.github.mvolkert.entryrecorder.data.local.prefs

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * What the first-run flow knows about this install.
 *
 * Room cannot answer before the window inflates, and [io.github.mvolkert.entryrecorder.ui.MainActivity]
 * has to know whether the intro is owed before it composes anything — so this is the same synchronous
 * SharedPreferences mirror as [io.github.mvolkert.entryrecorder.ui.theme.UiModePrefs] and
 * [AutoBootPrefs], not a settings column.
 *
 * The per-permission ask counter is here rather than inferred from
 * [androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale] because that call needs an
 * Activity, cannot tell "never asked" apart from "denied until the sheet stops appearing", and would
 * make the flow's action table depend on a state the app never recorded.
 */
object OnboardingPrefs {
    private const val PREFS = "onboarding"
    private const val KEY_COMPLETED = "completed"
    private const val KEY_ASK_PREFIX = "ask_"

    fun isCompleted(context: Context): Boolean = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_COMPLETED, false)

    fun setCompleted(context: Context, completed: Boolean) {
        // commit rather than the default apply: this file is synchronous for one reason — MainActivity has to
        // trust the answer before the first frame. A queued apply can be lost to a process death, which would
        // put a finished flow back on screen next open, the exact nag this file exists to stop.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit(commit = true) { putBoolean(KEY_COMPLETED, completed) }
    }

    /**
     * Forgets everything the intro recorded about this install: the completion answer and every ask
     * counter, so the flow both re-appears on the next launch and can fire the system sheet again instead
     * of routing a twice-denied permission to phone settings. Deliberately a wipe rather than a per-key
     * write — this file holds nothing but the intro's own state.
     */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
    }

    fun askCount(context: Context, permissionKey: String): Int = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_ASK_PREFIX + permissionKey, 0)

    /**
     * Stays on the asynchronous apply: losing one count costs an extra prompt at most, where losing
     * [setCompleted] costs the whole flow coming back.
     */
    fun recordAsk(context: Context, permissionKey: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putInt(KEY_ASK_PREFIX + permissionKey, prefs.getInt(KEY_ASK_PREFIX + permissionKey, 0) + 1) }
    }
}

/**
 * The [OnboardingPrefs] facts behind a JVM-only contract, so the flow's ViewModel and permission seam
 * hold no `Context` — the split [AutoBootStore] and [io.github.mvolkert.entryrecorder.ui.theme.ThemeModeStore]
 * already use.
 */
interface OnboardingStore {

    fun isCompleted(): Boolean

    fun setCompleted(completed: Boolean)

    /** Wipes the completion answer and every ask counter; what Settings' reset row calls. */
    fun reset()

    /** How many times the system sheet has been fired for [permissionKey], newest first ask at zero. */
    fun askCount(permissionKey: String): Int

    fun recordAsk(permissionKey: String)
}

/** The device-backed implementation: SharedPreferences through the application context. */
class AndroidOnboardingStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : OnboardingStore {

    override fun isCompleted(): Boolean = OnboardingPrefs.isCompleted(context)

    override fun setCompleted(completed: Boolean) = OnboardingPrefs.setCompleted(context, completed)

    override fun reset() = OnboardingPrefs.reset(context)

    override fun askCount(permissionKey: String): Int = OnboardingPrefs.askCount(context, permissionKey)

    override fun recordAsk(permissionKey: String) = OnboardingPrefs.recordAsk(context, permissionKey)
}
