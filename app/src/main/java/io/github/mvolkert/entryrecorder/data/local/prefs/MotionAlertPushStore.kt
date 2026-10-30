package io.github.mvolkert.entryrecorder.data.local.prefs

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Device-local state for receiving motion alerts (change `add-motion-notification-to-other-phone`).
 *
 * Deliberately SharedPreferences and not the Room settings row: the push endpoint belongs to *this install and
 * this distributor*, so a settings backup restored onto another phone would hand it an address that can never
 * work. The endpoint is re-derived by registering again, which is exactly what happens on a fresh install, so
 * nothing here has to survive a restore. Same split as [AutoBootPrefs]: a synchronous store the push service
 * can write from a binder thread with no coroutine, plus a JVM-only contract for the ViewModel.
 */
object MotionAlertPushPrefs {
    private const val PREFS = "motion_alert_push"
    private const val KEY_RECEIVE_ENABLED = "receiveEnabled"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_NEEDS_ENCRYPTION = "endpointNeedsEncryption"

    fun isReceiveEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_RECEIVE_ENABLED, false)

    fun endpoint(context: Context): String? = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_ENDPOINT, null)

    fun endpointNeedsEncryption(context: Context): Boolean = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_NEEDS_ENCRYPTION, false)

    fun setReceiveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_RECEIVE_ENABLED, enabled) }
    }

    fun saveEndpoint(context: Context, url: String, needsEncryption: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_ENDPOINT, url)
                putBoolean(KEY_NEEDS_ENCRYPTION, needsEncryption)
            }
    }

    fun clearEndpoint(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                remove(KEY_ENDPOINT)
                putBoolean(KEY_NEEDS_ENCRYPTION, false)
            }
    }
}

/** The receiver state as a JVM-only contract, so the settings ViewModel and the push handler hold no Context. */
interface MotionAlertPushStore {

    /** Whether this phone is opted in to receiving relayed motion alerts. */
    fun isReceiveEnabled(): Boolean

    /** The address the distributor handed this install, or null until registration lands. */
    fun endpoint(): String?

    /** Whether that address belongs to a distributor that needs encrypted (Web Push) delivery. */
    fun endpointNeedsEncryption(): Boolean

    fun setReceiveEnabled(enabled: Boolean)

    fun saveEndpoint(url: String, needsEncryption: Boolean)

    /** Drops the address when the distributor says this registration is gone. */
    fun clearEndpoint()
}

/** The device-backed implementation: SharedPreferences through the application context. */
class AndroidMotionAlertPushStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : MotionAlertPushStore {

    override fun isReceiveEnabled(): Boolean = MotionAlertPushPrefs.isReceiveEnabled(context)

    override fun endpoint(): String? = MotionAlertPushPrefs.endpoint(context)

    override fun endpointNeedsEncryption(): Boolean = MotionAlertPushPrefs.endpointNeedsEncryption(context)

    override fun setReceiveEnabled(enabled: Boolean) = MotionAlertPushPrefs.setReceiveEnabled(context, enabled)

    override fun saveEndpoint(url: String, needsEncryption: Boolean) =
        MotionAlertPushPrefs.saveEndpoint(context, url, needsEncryption)

    override fun clearEndpoint() = MotionAlertPushPrefs.clearEndpoint(context)
}
