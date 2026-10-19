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

    fun hasAnswer(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_COMPLETED)

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

    /** Whether the install has any answer recorded; what tells a fresh install from an upgraded one. */
    fun hasAnswer(): Boolean

    fun isCompleted(): Boolean

    fun setCompleted(completed: Boolean)

    /** How many times the system sheet has been fired for [permissionKey], newest first ask at zero. */
    fun askCount(permissionKey: String): Int

    fun recordAsk(permissionKey: String)
}

/** The device-backed implementation: SharedPreferences through the application context. */
class AndroidOnboardingStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : OnboardingStore {

    override fun hasAnswer(): Boolean = OnboardingPrefs.hasAnswer(context)

    override fun isCompleted(): Boolean = OnboardingPrefs.isCompleted(context)

    override fun setCompleted(completed: Boolean) = OnboardingPrefs.setCompleted(context, completed)

    override fun askCount(permissionKey: String): Int = OnboardingPrefs.askCount(context, permissionKey)

    override fun recordAsk(permissionKey: String) = OnboardingPrefs.recordAsk(context, permissionKey)
}
