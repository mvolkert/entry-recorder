package io.github.mvolkert.entryrecorder.service

import io.github.mvolkert.entryrecorder.domain.model.SystemTimeProvider
import io.github.mvolkert.entryrecorder.domain.model.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The hold decision behind the monitor: which facts may keep the CPU and radio awake, and when the pair is
 * dropped again. The Android mechanics live in [LockController]; nothing here touches a framework lock type,
 * which is what makes the policy testable.
 *
 * Built by the service rather than injected: it runs on the service's lazily-built scope, and a graph
 * singleton would tempt a second lock pair held past the service's death.
 *
 * [reasons] is supplied by the service because every fact it reports belongs to somebody else — the pending
 * post-record stops to [TriggerEpisodeRunner], the running clips and the live device rows to
 * [DeviceMonitorPool]. One supplier is read by both the renewal loop and the release path on purpose: two
 * sources of truth for "still needed?" is how a lock gets abandoned or held forever.
 *
 * Acquires are bounded and renewed while a reason holds, so a path that forgets to release lapses at its
 * timeout instead of pinning the CPU until the process dies.
 *
 * The log tag stays the service's own: a screen-off investigation reads one continuous stream, exactly as it
 * did before the split.
 */
class MonitorLockKeeper(
    private val controller: LockController,
    private val scope: CoroutineScope,
    private val timeProvider: TimeProvider = SystemTimeProvider,
    private val reasons: () -> Set<LockReason>,
) {

    // The renewal tick, while a reason holds the pair.
    private var renewal: Job? = null

    // Safety-net release for clips the recorder ends on its own duration deadline: the service never sees
    // those stops, so without it a trigger's lock would outlive the recording it was taken for.
    private var safetyRelease: Job? = null

    // End of the longest per-trigger hold still armed, so a renewal never shortens a clip below one window.
    private var holdUntilMs = 0L

    /**
     * Re-reads the reason set: takes or renews the locks while any reason applies, and drops them once none
     * does. The service calls this on every device-list reconcile, which is also when analyzers start and stop.
     */
    fun reconcile() {
        if (LockHoldPolicy.shouldHold(reasons())) takeHold() else releaseIfIdle()
    }

    /**
     * Holds the locks for at least [durationMs], then releases them if nothing else needs them. A trigger's
     * clip can outlast one acquire window, so its deadline is remembered and each renewal extends to it.
     */
    fun holdFor(durationMs: Long) {
        holdUntilMs = maxOf(holdUntilMs, timeProvider.currentTimeMillis() + durationMs)
        takeHold()
        safetyRelease?.cancel()
        safetyRelease = scope.launch {
            delay(durationMs)
            safetyRelease = null
            releaseIfIdle()
        }
    }

    /**
     * Drops a trigger's locks once no reason applies: nothing recording, no post-record timer pending, nobody
     * opted into holding them through idle and no motion analyzer polling.
     */
    fun releaseIfIdle() {
        if (LockHoldPolicy.shouldHold(reasons())) return
        stopRenewal()
        holdUntilMs = 0L
        if (controller.isHeld()) controller.release()
    }

    /** Unconditional release for service teardown, where no reason survives the service anyway. */
    fun releaseAll() {
        stopRenewal()
        holdUntilMs = 0L
        controller.release()
    }

    /** Stops the safety-net timer on the way out, so a dying service leaves no release scheduled behind it. */
    fun cancelSafetyRelease() {
        safetyRelease?.cancel()
    }

    private fun takeHold() {
        controller.acquire(holdTimeoutMs())
        ensureRenewal()
    }

    /** The next acquire window: the standing timeout, or the remainder of an armed trigger hold if longer. */
    private fun holdTimeoutMs(): Long =
        maxOf(LOCK_HOLD_TIMEOUT_MS, holdUntilMs - timeProvider.currentTimeMillis())

    private fun ensureRenewal() {
        if (renewal?.isActive == true) return
        renewal = scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(LOCK_RENEW_INTERVAL_MS)
                if (holdStillWanted()) controller.acquire(holdTimeoutMs()) else releaseIfIdle()
            }
        }
    }

    /**
     * Whether the tick should keep the pair: a reason still applies, or an armed [holdFor] deadline it was told
     * to cover has not passed. Without the second half, a trigger whose work the phone does not see (a
     * server-side clip) would lose its lock at the first tick.
     */
    private fun holdStillWanted(): Boolean =
        LockHoldPolicy.shouldHold(reasons()) || holdUntilMs > timeProvider.currentTimeMillis()

    private fun stopRenewal() {
        renewal?.cancel()
        renewal = null
    }

    internal companion object {
        /**
         * How long one acquire window lasts. Long enough that a missed renewal is survivable rather than a
         * detection gap, short enough that an abandoned hold cannot pin the CPU for hours.
         */
        const val LOCK_HOLD_TIMEOUT_MS = 10L * 60L * 1000L

        /** Half the window: a Doze- or handler-deferred tick still leaves a full timeout of cover. */
        const val LOCK_RENEW_INTERVAL_MS = 5L * 60L * 1000L
    }
}
