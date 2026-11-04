package io.github.mvolkert.entryrecorder.video

import io.github.mvolkert.entryrecorder.data.model.MotionSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The open/close rules on a clock the test steps itself. What is proven here is the unit split this change is
 * about: opening stays a count of changed comparisons however long the gaps between them were, and closing is a
 * duration the quiet frames themselves have to span, so the pace the loop happens to be polling at stops
 * deciding how long an episode is open.
 */
class MotionEpisodeTrackerTest {

    // The BALANCED rule: two changed comparisons open, two seconds of observed stillness close.
    private fun tracker(required: Int = 2, stillnessMs: Long = 2_000L) =
        MotionEpisodeTracker(requiredMotionFrames = required, clearStillnessMs = stillnessMs)

    /** Opens an episode on a scene that changed at [intervalMs] apart, returning the tracker. */
    private fun openedTracker(intervalMs: Long): MotionEpisodeTracker {
        val tracker = tracker()
        tracker.onComparison(changeDetected = true, elapsedMs = 0L)
        tracker.onComparison(changeDetected = true, elapsedMs = intervalMs)
        assertTrue(tracker.isEpisodeActive)
        return tracker
    }

    @Test
    fun `opening counts changed comparisons however long the intervals were`() {
        val tracker = tracker()
        assertEquals(MotionDecision.None, tracker.onComparison(true, 0L))
        // The second change arrives 5s late because the endpoint was slow: the count still opens the episode.
        assertEquals(
            MotionDecision.Started(frames = 2, spanMs = 5_000L),
            tracker.onComparison(true, 5_000L),
        )
    }

    @Test
    fun `a quiet frame inside a changed run restarts the run and its span`() {
        val tracker = tracker()
        assertEquals(MotionDecision.None, tracker.onComparison(true, 0L))
        assertEquals(MotionDecision.None, tracker.onComparison(false, 500L))
        assertEquals(MotionDecision.None, tracker.onComparison(true, 1_000L))
        assertEquals(
            "The span has to be measured from the second changed frame, not the first one that was broken up",
            MotionDecision.Started(frames = 2, spanMs = 2_000L),
            tracker.onComparison(true, 3_000L),
        )
    }

    @Test
    fun `the gap before the first quiet frame is not stillness`() {
        val tracker = openedTracker(intervalMs = 500L)
        // One quiet frame 29.5s after the last change: nothing was sampled in between, so nothing was seen quiet.
        assertEquals(MotionDecision.None, tracker.onComparison(false, 30_000L))
        assertEquals(MotionDecision.None, tracker.onComparison(false, 30_500L))
        assertEquals(
            MotionDecision.Ended(stillnessMs = 2_000L, frames = 3),
            tracker.onComparison(false, 32_000L),
        )
    }

    @Test
    fun `the preset's stillness duration closes the episode at any polling pace`() {
        listOf(500L, 1_500L, 2_500L).forEach { paceMs ->
            val tracker = openedTracker(paceMs)
            var elapsed = paceMs * 2
            var decision: MotionDecision = MotionDecision.None
            // The first quiet frame anchors the measurement, so the close lands on the duration rather than
            // counting polls.
            while (decision == MotionDecision.None) {
                elapsed += paceMs
                decision = tracker.onComparison(false, elapsed)
            }
            val ended = decision as MotionDecision.Ended
            assertFalse("polling at $paceMs must leave the episode closed", tracker.isEpisodeActive)
            assertTrue(
                "polling at $paceMs closed after ${ended.stillnessMs}ms, expected 2000ms plus at most one interval",
                ended.stillnessMs in 2_000L until 2_000L + paceMs,
            )
        }
    }

    @Test
    fun `a change inside the duration restarts the stillness measurement`() {
        val tracker = openedTracker(intervalMs = 500L)
        assertEquals(MotionDecision.None, tracker.onComparison(false, 1_000L))
        assertEquals(MotionDecision.None, tracker.onComparison(false, 1_500L))
        assertEquals(MotionDecision.None, tracker.onComparison(true, 2_000L))
        // The measurement now runs from the next quiet frame, so the 1.5s already logged cannot close it.
        assertEquals(MotionDecision.None, tracker.onComparison(false, 2_500L))
        assertEquals(MotionDecision.None, tracker.onComparison(false, 4_000L))
        assertEquals(
            MotionDecision.Ended(stillnessMs = 2_000L, frames = 3),
            tracker.onComparison(false, 4_500L),
        )
    }

    @Test
    fun `reset drops an open episode so a rebuilt analyzer does not carry one over`() {
        val tracker = openedTracker(intervalMs = 500L)
        tracker.reset()
        assertFalse(tracker.isEpisodeActive)
        // Quiet from here on is a fresh measurement, not the tail of the episode that was dropped.
        assertEquals(MotionDecision.None, tracker.onComparison(false, 10_000L))
        assertEquals(MotionDecision.None, tracker.onComparison(false, 20_000L))
    }

    @Test
    fun `each preset demands the stillness its quiet-frame count used to cost at its own base interval`() {
        assertEquals(1_200L, motionTuningFor(MotionSensitivity.SENSITIVE).clearStillnessMs)
        assertEquals(2_000L, motionTuningFor(MotionSensitivity.BALANCED).clearStillnessMs)
        assertEquals(4_800L, motionTuningFor(MotionSensitivity.POWER_SAVER).clearStillnessMs)
    }
}
