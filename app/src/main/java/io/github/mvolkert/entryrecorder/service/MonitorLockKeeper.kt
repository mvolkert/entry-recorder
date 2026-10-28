package io.github.mvolkert.entryrecorder.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The wake/Wi-Fi lock policy behind the monitor: what may keep the CPU and radio awake, and when that is
 * dropped again. Moved out of [IntercomMonitorService] with its private functions mapped one-to-one, so the
 * service keeps lifecycle and delegation only.
 *
 * Built by the service rather than injected: it runs on the service's lazily-built scope, and a graph
 * singleton would tempt a second lock pair held past the service's death.
 *
 * [workOutstanding] is supplied by the service because the two facts that keep a lock alive — a pending
 * post-record stop and a running local recording — live in [TriggerEpisodeRunner] and [DeviceMonitorPool],
 * not here. It is consulted in that order, as before.
 *
 * The log tag stays the service's own: a screen-off investigation reads one continuous stream, exactly as it
 * did before the split.
 */
class MonitorLockKeeper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val workOutstanding: () -> Boolean,
) {

    private val tag = "IntercomMonitorService"

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    // True while at least one monitored device opts into holding the locks through idle periods.
    private var continuousHold = false
    // Safety-net release for clips the recorder ends on its own duration deadline: the service never sees
    // those stops, so without it a trigger's lock would outlive the recording it was taken for.
    private var safetyRelease: Job? = null

    /**
     * Reconciles the policy with a new device list: one keepLocksHeld device wins and holds through idle;
     * when the last of them goes away, anything still held is released once nothing records.
     */
    fun setHoldThroughIdle(holdThroughIdle: Boolean) {
        continuousHold = holdThroughIdle
        if (continuousHold) acquireForTrigger() else releaseIfIdle()
    }

    /**
     * Takes the CPU and Wi-Fi locks for as long the monitored work needs them. Acquiring an already-held
     * lock is a no-op, so overlapping triggers share one pair and a single [releaseAll] ends it.
     */
    fun acquireForTrigger() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wake = wakeLock ?: powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EntryRecorder::MonitorWakeLock"
            ).also { wakeLock = it }
            if (!wake.isHeld) wake.acquire()

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifi = wifiLock ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ exposes a tag-only factory; the int-mode overload is deprecated.
                wifiManager.createWifiLock("EntryRecorder::MonitorWifiLock")
            } else {
                @Suppress("DEPRECATION") // Owner-approved legacy branch: pre-Q has no tag-only WifiLock API.
                wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "EntryRecorder::MonitorWifiLock"
                )
            }).also { wifiLock = it }
            if (!wifi.isHeld) wifi.acquire()
        } catch (e: Exception) {
            Timber.tag(tag).w(e, "Could not acquire Wake/WiFi locks")
        }
    }

    /** Holds the locks for at least [durationMs], then releases them if nothing else needs them. */
    fun holdFor(durationMs: Long) {
        acquireForTrigger()
        safetyRelease?.cancel()
        safetyRelease = scope.launch {
            delay(durationMs)
            safetyRelease = null
            releaseIfIdle()
        }
    }

    /** Drops a trigger's locks once no device records, no post-record timer is pending and nobody opted into holding them through idle. */
    fun releaseIfIdle() {
        if (continuousHold) return
        if (workOutstanding()) return
        releaseAll()
    }

    fun releaseAll() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // A lock left held after release keeps the CPU or radio awake with nobody to let it go,
            // so a failed release has to be visible even though there is nothing left to handle it.
            Timber.tag(tag).w(e, "Failed to release the wake/WiFi locks")
        }
    }

    /** Stops the safety-net timer on the way out, so a dying service leaves no release scheduled behind it. */
    fun cancelSafetyRelease() {
        safetyRelease?.cancel()
    }
}
