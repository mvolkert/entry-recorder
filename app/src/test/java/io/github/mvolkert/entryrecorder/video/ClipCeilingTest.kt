package io.github.mvolkert.entryrecorder.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure arithmetic behind a per-trigger recording ceiling: a fold may push a running clip's end
 * out, but never past the ceiling the device configured, and a clip that has already outlived its ceiling is
 * left alone rather than shortened under the running muxer. Plus the frame-cap halt that stops the capture
 * loop at the exact configured count while the triggering event is still ongoing.
 */
class ClipCeilingTest {

    private val startMs = 1_000_000L
    private val ceilingMs = startMs + 60_000L

    @Test
    fun `the capture loop halts at its exact frame ceiling`() {
        assertTrue(frameCapReached(maxFrames = 3, framesWritten = 3))
        assertTrue(frameCapReached(maxFrames = 3, framesWritten = 4))
        assertFalse(frameCapReached(maxFrames = 3, framesWritten = 2))
    }

    @Test
    fun `a zero frame ceiling never halts the loop early`() {
        assertFalse(frameCapReached(maxFrames = 0, framesWritten = 0))
        assertFalse(frameCapReached(maxFrames = 0, framesWritten = 100_000))
    }

    @Test
    fun `a fold inside the ceiling extends the clip`() {
        val current = startMs + 20_000L
        val wanted = startMs + 40_000L
        assertEquals(wanted, cappedDeadlineMs(current, wanted, ceilingMs))
    }

    @Test
    fun `a fold never raises the deadline past the ceiling`() {
        val current = startMs + 20_000L
        assertEquals(ceilingMs, cappedDeadlineMs(current, startMs + 300_000L, ceilingMs))
    }

    @Test
    fun `a fold never shortens what the clip already had`() {
        // Repeated rings each ask for another minute; the deadline still only reaches the ceiling, and a
        // ceiling set after the fact cannot pull an already-armed end back in.
        val current = ceilingMs
        assertEquals(current, cappedDeadlineMs(current, startMs + 5_000L, current + 60_000L))
    }

    @Test
    fun `an unlimited ceiling leaves the extension untouched`() {
        val current = startMs + 20_000L
        val wanted = startMs + 3_600_000L
        assertEquals(wanted, cappedDeadlineMs(current, wanted, NO_RECORDING_CEILING_MS))
    }
}
