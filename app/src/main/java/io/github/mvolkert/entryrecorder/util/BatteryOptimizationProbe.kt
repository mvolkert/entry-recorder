package io.github.mvolkert.entryrecorder.util

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Whether the system lets this app run without battery management, behind a seam so both readers of the fact —
 * the first-run page and the always-on monitor — ask the same question of the same platform service.
 */
interface BatteryOptimizationProbe {

    /** True when Doze and app standby will not suspend this app's polling. */
    fun isIgnoringOptimizations(): Boolean
}

/** [BatteryOptimizationProbe] over the platform's per-package exemption state. */
class AndroidBatteryOptimizationProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) : BatteryOptimizationProbe {

    override fun isIgnoringOptimizations(): Boolean {
        // No power service means nothing to exempt, so the answer is "already done" rather than a warning
        // about a setting that does not exist on this device.
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }
}

/** What one battery-exemption read is worth in the monitor log. */
enum class BatteryExemptionNotice {
    /** Nothing changed since the last report, so nothing is logged. */
    None,

    /** The app is subject to battery optimization: the state that can suspend snapshot polling. */
    NotExempt,

    /** The app is exempt, which is what a detection phone needs. */
    NowExempt,
}

/**
 * Turns the repeated battery-exemption reads of a long-running monitor into change-of-state reports only, so a
 * phone that is subject to battery optimization warns once instead of on every device-list emission. Same
 * edge-triggered idiom as [ConnectionEdgeTracker]: a first read counts as a change, a repeated read does not, and
 * the line comes back if the state flips again. Pure Kotlin; the caller supplies the read. Not thread-safe: the
 * monitor serialises its reconciles.
 */
class BatteryExemptionReport {

    private var lastReported: Boolean? = null

    /** The notice the monitor should log for [exempt], or [BatteryExemptionNotice.None] if it already said so. */
    fun noticeFor(exempt: Boolean): BatteryExemptionNotice {
        val previous = lastReported
        if (previous == exempt) return BatteryExemptionNotice.None
        lastReported = exempt
        return if (exempt) BatteryExemptionNotice.NowExempt else BatteryExemptionNotice.NotExempt
    }
}
