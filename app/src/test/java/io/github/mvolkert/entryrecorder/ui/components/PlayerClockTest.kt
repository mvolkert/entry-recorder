package io.github.mvolkert.entryrecorder.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The transport row shows a clock, not a frame count, so the whole contract is rounding and shape: a
 * position reads as `m:ss` until it passes an hour and then grows the hour in front of it. Frame indexes
 * are never displayed here, and a damaged MKV timestamp must not be able to print a negative time.
 */
class PlayerClockTest {

    @Test
    fun `zero and a partial second read as no elapsed time`() {
        assertEquals("0:00", formatPlayerClock(0L))
        assertEquals("0:00", formatPlayerClock(999L))
        // The last frame before a minute turns over is still 9 seconds, not 10.
        assertEquals("0:09", formatPlayerClock(9_999L))
    }

    @Test
    fun `minutes are not padded but seconds always are`() {
        assertEquals("0:10", formatPlayerClock(10_000L))
        // 613 s = ten minutes thirteen seconds.
        assertEquals("10:13", formatPlayerClock(613_000L))
    }

    @Test
    fun `an hour rolls over and pads the minute field`() {
        // 3661 s = one hour, one minute, one second.
        assertEquals("1:01:01", formatPlayerClock(3_661_000L))
        assertEquals("1:00:00", formatPlayerClock(3_600_000L))
        assertEquals("25:30:45", formatPlayerClock(91_845_000L))
    }

    @Test
    fun `a negative timestamp clamps instead of counting down`() {
        assertEquals("0:00", formatPlayerClock(-1L))
        assertEquals("0:00", formatPlayerClock(-613_000L))
    }
}
