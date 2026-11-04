package io.github.mvolkert.entryrecorder.video

import io.github.mvolkert.entryrecorder.data.model.MotionSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analyzer's own numbers. Everything a line reports is something the analysis loop produced: the rate is the
 * comparisons it scored divided by the window it measured, so a co-tenant polling the same endpoint at the
 * configured capture rate cannot lift the figure, and a scene the loop backed off on reads as the slow cadence it
 * is rather than as a camera that stopped working.
 */
class MotionAnalysisReportTest {

    private fun window(
        scored: Int,
        polls: Int,
        windowMs: Long,
        pollMs: Long,
        recording: Boolean = false,
        peakChangedRatio: Float = 0.04f,
    ) = MotionWindow(
        deviceName = "EmuA",
        scoredFrames = scored,
        polls = polls,
        peakChangedRatio = peakChangedRatio,
        windowMs = windowMs,
        pollMs = pollMs,
        recording = recording,
        analysisWidth = 96,
        analysisHeight = 54,
    )

    @Test
    fun `the scored rate divides the analyzer's own comparisons by the window it measured`() {
        assertEquals(
            2.0,
            MotionAnalysisReport.scoredFps(window(scored = 120, polls = 150, windowMs = 60_000L, pollMs = 500L))
                .toDouble(),
            0.0,
        )
        // A window that ran long because the loop was descheduled reports the rate it really delivered.
        assertEquals(
            1.0,
            MotionAnalysisReport.scoredFps(window(scored = 120, polls = 150, windowMs = 120_000L, pollMs = 500L))
                .toDouble(),
            0.0,
        )
        assertEquals(
            0.0,
            MotionAnalysisReport.scoredFps(window(scored = 0, polls = 12, windowMs = 60_000L, pollMs = 500L))
                .toDouble(),
            0.0,
        )
    }

    /**
     * The field set in one assertion: renaming a field, changing a unit or dropping the cadence has to be a
     * deliberate edit here. The old wording was `poll 500ms, 2.0 fps achieved (configured 5)`, whose configured
     * number came from the device row and whose achieved number came from the shared snapshot client.
     */
    @Test
    fun `the health line states what the analyzer scored over the time it measured`() {
        assertEquals(
            "Motion analysis on EmuA: 119/150 polls scored, peak change 4% of 96x54 " +
                "(needs 3% on 2 in a row, BALANCED), 2.0 fps over 60.0s, poll 500ms (base)",
            MotionAnalysisReport.health(
                window(scored = 119, polls = 150, windowMs = 60_000L, pollMs = 500L),
                motionTuningFor(MotionSensitivity.BALANCED),
                MotionSensitivity.BALANCED,
            ),
        )
    }

    @Test
    fun `a backed-off window names its interval instead of reading as a broken camera`() {
        val line = MotionAnalysisReport.health(
            window(scored = 24, polls = 24, windowMs = 60_000L, pollMs = 2_500L),
            motionTuningFor(MotionSensitivity.POWER_SAVER),
            MotionSensitivity.POWER_SAVER,
        )
        assertTrue(line, line.contains("24/24 polls scored"))
        assertTrue(line, line.contains("0.4 fps over 60.0s"))
        assertTrue(line, line.contains("poll 2500ms (idle backoff, ceiling 2500ms)"))
        // Nothing in the line is an endpoint-side estimate of any other consumer's rate.
        assertFalse(line, line.contains("configured"))
    }

    @Test
    fun `a recording forces the slow analyzer pace into the line`() {
        val line = MotionAnalysisReport.health(
            window(scored = 40, polls = 40, windowMs = 60_000L, pollMs = 1_500L, recording = true),
            motionTuningFor(MotionSensitivity.BALANCED),
            MotionSensitivity.BALANCED,
        )
        assertTrue(line, line.contains("0.7 fps over 60.0s"))
        assertTrue(line, line.contains("poll 1500ms (recording pace)"))
    }

    @Test
    fun `no frames is a supply statement and never a rate`() {
        val starved = MotionAnalysisReport.noFrames(window(scored = 0, polls = 150, windowMs = 60_000L, pollMs = 500L))
        assertEquals(
            "No analysis frames from EmuA: 150 polls, 0 usable JPEGs in 60.0s — endpoint not serving images " +
                "(check the HttpSnapshotClient log lines)",
            starved,
        )
        assertFalse(starved, starved.contains("fps"))

        val backedOff = MotionAnalysisReport.health(
            window(scored = 24, polls = 24, windowMs = 60_000L, pollMs = 2_500L),
            motionTuningFor(MotionSensitivity.POWER_SAVER),
            MotionSensitivity.POWER_SAVER,
        )
        assertNotEquals(starved, backedOff)
        assertTrue(backedOff, backedOff.contains("0.4 fps"))
    }

    @Test
    fun `decision lines name the span they were taken over`() {
        assertEquals(
            "Motion start on EmuA: 7% changed pixels, over 2 frames in 0.5s",
            MotionAnalysisReport.start("EmuA", changedRatio = 0.077f, frames = 2, spanMs = 500L),
        )
        assertEquals(
            "Motion end on EmuA after 2.0s still (5 frames)",
            MotionAnalysisReport.end("EmuA", stillnessMs = 2_000L, frames = 5),
        )
    }
}
