package io.github.mvolkert.entryrecorder.domain.triggers

import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.model.SystemTimeProvider
import io.github.mvolkert.entryrecorder.domain.model.TimeProvider
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * The trigger-routing policy the monitor service owes its callers, as pure Kotlin so it can be unit-tested
 * without a Service, locks or a wall clock: whether a doorbell ring is a fresh press or a duplicate of one
 * already handled, and how long each trigger's recording and lock hold should last.
 *
 * The service keeps every Android mechanic (foreground service, notifications, wake locks, activity intents,
 * coroutine timers) and asks this coordinator only for the *decision*; the timer that fires a deferred stop
 * stays in the service because scheduling is not a policy question.
 *
 * @param timeProvider the clock the debounce compares against; tests inject a fixed one instead of sleeping.
 */
class TriggerCoordinator(
    private val timeProvider: TimeProvider = SystemTimeProvider,
) {
    // Last handled doorbell ring per device, in wall-clock ms, for the duplicate-ring debounce.
    private val lastRingHandledAt = ConcurrentHashMap<Long, Long>()

    /**
     * True when [deviceId]'s ring is a fresh press worth handling. One physical press reaches the app from up
     * to three sources within a second (2N KeyPressed, 2N CallStateChanged and the SIP INVITE), so a ring
     * arriving inside [RING_DEBOUNCE_MS] of the last handled one is the same press and is collapsed. Note that
     * this also swallows a genuine second press inside the window (the running clip folds it, no second alert).
     */
    fun shouldHandleRing(deviceId: Long): Boolean {
        val now = timeProvider.currentTimeMillis()
        val previous = lastRingHandledAt.put(deviceId, now)
        return previous == null || now - previous >= RING_DEBOUNCE_MS
    }

    /** Drops a device's debounce stamp so a re-added id never inherits the old ring timing. */
    fun forgetDevice(deviceId: Long) {
        lastRingHandledAt.remove(deviceId)
    }

    /** Clears every stamp; called when the service tears down. */
    fun reset() {
        lastRingHandledAt.clear()
    }

    /** How long the post-record buffer runs for [eventType] once its *Ended event arrives. */
    fun postRecordSeconds(device: Device, eventType: EventType): Int = when (eventType) {
        EventType.MOTION -> device.motionPostRecordSeconds
        EventType.NOISE -> device.noisePostRecordSeconds
        EventType.RING, EventType.MANUAL -> device.ringRecordSeconds
        // An unrecognised label is never a live trigger (the device path only emits the four above), so it
        // asks for no buffer rather than guessing one of the real triggers' timings.
        EventType.UNKNOWN -> 0
    }

    /**
     * The recording length armed for a motion/noise trigger: the post-record buffer plus
     * [TRIGGER_RECORD_HEADROOM_SECONDS], brought under [recordCeilingSeconds] where the device sets one.
     * The *Ended event is what normally stops the clip, so the timer only has to outlive a missing end
     * event rather than define the clip — unless a ceiling exists, which is a hard bound.
     */
    fun triggerRecordDurationSeconds(device: Device, eventType: EventType): Int =
        capToCeiling(
            postRecordSeconds(device, eventType) + TRIGGER_RECORD_HEADROOM_SECONDS,
            recordCeilingSeconds(device, eventType),
        )

    /** The recording length an active ring asks for, under the device's ring ceiling when it sets one. */
    fun ringRecordDurationSeconds(device: Device): Int =
        capToCeiling(device.ringRecordSeconds, recordCeilingSeconds(device, EventType.RING))

    /**
     * How long an active trigger holds the CPU and radio awake: the full [recordSeconds] clip deadline plus
     * [TRIGGER_LOCK_MARGIN_MS], so a finalising stop never races the safety release and drops the CPU mid-write.
     */
    fun lockHoldDurationMs(recordSeconds: Int): Long =
        recordSeconds * 1000L + TRIGGER_LOCK_MARGIN_MS

    private fun capToCeiling(armedSeconds: Int, ceilingSeconds: Int?): Int =
        ceilingSeconds?.let { minOf(armedSeconds, it) } ?: armedSeconds

    companion object {
        /** Rings inside this window after a handled one collapse into the same press. */
        const val RING_DEBOUNCE_MS = 5000L

        /** Extra seconds past the post-record buffer a trigger recording is armed for. */
        const val TRIGGER_RECORD_HEADROOM_SECONDS = 30

        /** Extra milliseconds a trigger's locks are held past its recording deadline. */
        const val TRIGGER_LOCK_MARGIN_MS = 15_000L

        /**
         * The hard ceiling a trigger's clip may not pass, in seconds, or null when the device asks for no
         * limit. Motion/noise are configured in frames (what the capture loop writes), so the count converts
         * through the device's snapshot rate and rounds up — a partial second still gets its frames instead of
         * losing the last one to truncation. Ring is configured in minutes. A manual clip is the user pressing
         * Record rather than a trigger the device produced, so it keeps its own length.
         *
         * On the companion because the phone-side capture loop enforces the same ceiling and is not built with
         * a coordinator instance.
         */
        fun recordCeilingSeconds(device: Device, eventType: EventType): Int? {
            val maxFrames = device.maxFramesFor(eventType)
            if (maxFrames > 0) return ceil(maxFrames.toDouble() / device.effectiveSnapshotFps).toInt()
            return when (eventType) {
                EventType.RING -> device.effectiveRingMaxMinutes.takeIf { it > 0 }?.let { it * 60 }
                else -> null
            }
        }
    }
}
