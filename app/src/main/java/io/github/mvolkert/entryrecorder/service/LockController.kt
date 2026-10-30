package io.github.mvolkert.entryrecorder.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import timber.log.Timber

/**
 * The Android lock mechanics the monitor needs, as three calls. What [MonitorLockKeeper] decides — whether a
 * reason exists to stay awake — is pure and tested; this is the part that talks to PowerManager and WifiManager,
 * which no JVM test can exercise.
 */
interface LockController {

    /**
     * Takes the wake and Wi-Fi pair. [timeoutMs] is the platform timeout of the wake lock; the Wi-Fi lock has no
     * timed acquire in the framework, so it is bounded by whoever calls this releasing it (see
     * [AndroidLockController]). A refresh of a window already open replaces its deadline.
     */
    fun acquire(timeoutMs: Long)

    /** Drops both locks. */
    fun release()

    /** Whether either lock is still held; an acquire that reached its timeout reads as not held. */
    fun isHeld(): Boolean
}

/**
 * [LockController] over one partial wake lock and one high-performance Wi-Fi lock, both non-reference-counted.
 *
 * [WifiManager.WifiLock] only has an untimed `acquire()`, so the radio half cannot carry its own deadline the way
 * the wake lock does. It stays bounded by the holder instead: [MonitorLockKeeper] re-acquires on its renewal tick
 * and releases both locks as soon as no reason is left, so an idle monitor drops the pair at the next tick rather
 * than holding the radio until the service dies.
 */
class AndroidLockController(context: Context) : LockController {

    private val appContext = context.applicationContext
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    override fun acquire(timeoutMs: Long) {
        try {
            wake().acquire(timeoutMs)
            // No timed overload exists for the Wi-Fi lock; the keeper's renewal tick releases it once no
            // reason holds it, which is the only bound available on this half of the pair.
            wifi().acquire()
        } catch (e: Exception) {
            Timber.tag(MONITOR_LOG_TAG).w(e, "Could not acquire Wake/WiFi locks")
        }
    }

    @Synchronized
    override fun release() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // A lock left held after a failed release keeps the CPU or radio awake with nobody to let it go,
            // so the failure has to be visible even though there is nothing left to do about it.
            Timber.tag(MONITOR_LOG_TAG).w(e, "Failed to release the wake/WiFi locks")
        }
    }

    @Synchronized
    override fun isHeld(): Boolean = wakeLock?.isHeld == true || wifiLock?.isHeld == true

    /**
     * Deliberately not reference counted: the monitor owns one pair, so renewing has to refresh that single
     * acquire rather than stack a count that would need the same number of releases to let go. Both halves of
     * the pair need this, and the Wi-Fi lock has to be told explicitly (see [wifi]).
     */
    private fun wake(): PowerManager.WakeLock = wakeLock ?: run {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
    }

    private fun wifi(): WifiManager.WifiLock = wifiLock ?: run {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+ exposes a tag-only factory; the int-mode overload is deprecated.
            wifiManager.createWifiLock(WIFI_LOCK_TAG)
        } else {
            @Suppress("DEPRECATION") // Owner-approved legacy branch: pre-Q has no tag-only WifiLock API.
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
        }).apply {
            // The framework constructs a WifiLock reference counted, so each renewal acquire would raise a count
            // that one release cannot zero and the radio would never sleep again. Same reasoning as [wake].
            setReferenceCounted(false)
        }.also { wifiLock = it }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "EntryRecorder::MonitorWakeLock"
        const val WIFI_LOCK_TAG = "EntryRecorder::MonitorWifiLock"
    }
}
