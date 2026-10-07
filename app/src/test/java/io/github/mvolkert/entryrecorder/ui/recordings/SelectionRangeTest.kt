package io.github.mvolkert.entryrecorder.ui.recordings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "mark first, mark last" range is pure index arithmetic over the on-screen row order, so the whole
 * gesture contract is provable without Compose: both ends included, direction-independent, and a missing
 * anchor (nothing picked yet, or the anchor row filtered/deleted away) collapses to the pressed row alone.
 */
class SelectionRangeTest {

    // The gallery lists newest first, so this is the visible order, not chronological order.
    private val visible = listOf(10L, 9L, 8L, 7L, 6L)

    @Test
    fun `range from an earlier anchor to a later target covers both ends`() {
        assertEquals(setOf(10L, 9L, 8L, 7L), rangeSelection(visible, anchorId = 10L, targetId = 7L))
    }

    @Test
    fun `reversed anchors produce the same range`() {
        assertEquals(setOf(10L, 9L, 8L, 7L), rangeSelection(visible, anchorId = 7L, targetId = 10L))
    }

    @Test
    fun `anchoring on the target itself selects one row`() {
        assertEquals(setOf(8L), rangeSelection(visible, anchorId = 8L, targetId = 8L))
    }

    @Test
    fun `a missing anchor selects only the target`() {
        assertEquals(setOf(6L), rangeSelection(visible, anchorId = null, targetId = 6L))
    }

    @Test
    fun `an anchor filtered out of the visible rows selects only the target`() {
        assertEquals(setOf(9L), rangeSelection(visible, anchorId = 42L, targetId = 9L))
    }

    @Test
    fun `a target that is not visible degrades to the target alone`() {
        assertEquals(setOf(99L), rangeSelection(visible, anchorId = 10L, targetId = 99L))
    }
}
