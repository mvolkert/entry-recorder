package io.github.mvolkert.entryrecorder.ui.recordings

import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.domain.model.Recording
import io.github.mvolkert.entryrecorder.testing.assertEquals
import io.github.mvolkert.entryrecorder.testing.assertFalse
import io.github.mvolkert.entryrecorder.testing.assertTrue
import io.github.mvolkert.entryrecorder.testing.testRecording
import org.junit.Test

/**
 * The multi-select state machine the gallery header and the bulk dialogs read: a range only ever adds, it is
 * measured on the order the screen shows, a deleted row leaves the batch, and the reported counts are the batch
 * a bulk operation will actually act on — including the checks a filter currently hides.
 */
class GallerySelectionTest {
    private val first = testRecording(id = 1, eventType = EventType.MOTION, filePath = "1.mkv")
    private val second = testRecording(id = 2, eventType = EventType.MOTION, filePath = "2.mkv")
    private val third = testRecording(id = 3, eventType = EventType.MOTION, filePath = "3.mkv")
    private val all = listOf(first, second, third)

    @Test
    fun `toggle checks and unchecks one row and leaves it as the anchor`() {
        val selection = GallerySelection()

        selection.toggle(1L)
        assertEquals(setOf(1L), selection.currentIds)
        selection.toggle(1L)
        assertTrue(selection.isEmpty)

        selection.toggle(2L)
        // The anchor is the row a later long-press measures from, so it survives the check it came from.
        selection.toggle(3L)
        assertEquals(setOf(2L, 3L), selection.currentIds)
    }

    @Test
    fun `a range covers the rows between the anchors in screen order and only ever adds`() {
        val selection = GallerySelection()
        selection.toggle(1L)

        // The screen shows row 3 above the anchor, so it is outside the range even though the archive order
        // would put it between 1 and 2 chronologically.
        selection.selectRangeTo(visibleIds = listOf(3L, 1L, 2L), targetId = 2L)

        assertEquals(setOf(1L, 2L), selection.currentIds)
        assertFalse(3L in selection.currentIds, "a range is measured on the visible order, not on ids")

        // Only adds: deselecting stays a per-row action, so a range over an empty selection stays empty.
        val fresh = GallerySelection()
        fresh.selectRangeTo(visibleIds = listOf(1L, 2L), targetId = 2L)
        assertEquals(setOf(2L), fresh.currentIds, "a range without an anchor degrades to the target row")
    }

    @Test
    fun `a deleted row leaves the batch and cannot anchor a later range`() {
        val selection = GallerySelection()
        selection.toggle(1L)
        selection.toggle(2L)

        selection.drop(1L)

        assertEquals(setOf(2L), selection.currentIds)
        // The anchor was 2 and stays 2; dropping the *other* checked row must not move it.
        selection.drop(2L)
        assertTrue(selection.isEmpty)
        selection.selectRangeTo(visibleIds = listOf(1L, 2L, 3L), targetId = 3L)
        assertEquals(setOf(3L), selection.currentIds, "the cleared anchor degrades the range to its target")
    }

    @Test
    fun `selectAll takes exactly the rows the filters let through`() {
        val selection = GallerySelection()

        selection.selectAll(listOf(1L, 3L))

        assertEquals(setOf(1L, 3L), selection.currentIds)
    }

    @Test
    fun `targetsIn resolves the batch against the whole archive, not the visible rows`() {
        val selection = GallerySelection()
        selection.toggle(2L)

        assertEquals(listOf(second), selection.targetsIn(all))
        assertEquals(emptyList<Recording>(), selection.targetsIn(listOf(first, third)), "a checked id that is gone is not in the batch")

        // A bulk action snapshots the ids before it launches, so a later check is not swept into it.
        val snapshot = selection.currentIds
        selection.toggle(3L)
        assertEquals(listOf(second), selection.targetsIn(all, snapshot))
    }

    @Test
    fun `info counts protected rows and the checks a filter hides`() {
        val selection = GallerySelection()
        val protectedSecond = second.copy(isProtected = true)
        selection.toggle(2L)
        selection.toggle(3L)

        val visibleOnly = listOf(3L)
        val info = selection.info(recordings = listOf(first, protectedSecond, third), visibleIds = visibleOnly, ids = selection.currentIds)

        assertEquals(2, info.selectedCount)
        assertEquals(1, info.protectedCount)
        assertEquals(1, info.hiddenSelectedCount, "row 2 is checked but currently filtered away")
    }

    @Test
    fun `info on an empty selection reports nothing`() {
        val info = GallerySelection().info(recordings = all, visibleIds = listOf(1L, 2L, 3L), ids = emptySet())

        assertEquals(SelectionInfo(0, 0), info)
    }
}
