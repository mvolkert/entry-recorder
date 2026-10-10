package io.github.mvolkert.entryrecorder.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The export folder holds the user's archive next to files the app never wrote, and by the time the
 * external pass runs the internal pass has already deleted the database rows behind the oldest clips.
 * So the name is the only identity left: everything here is about reading it correctly and about
 * refusing to guess when a name does not match a pattern this app actually produces.
 */
class SafExportNameTest {

    @Test
    fun `recognizes every name this app writes`() {
        listOf(
            "REC_3_MOTION_20240615_083012.mkv",
            "REC_3_MOTION_20240615_083012_h264.mp4",
            "server_rec_5.mp4",
        ).forEach { assertTrue("$it belongs to the app", SafExportName.isAppCreated(it)) }
    }

    @Test
    fun `a thumbnail is not something the app exports`() {
        // Thumbnails live in the app's own files dir and are never written through SAF; the `THUMB_` images
        // that do turn up in a folder come from the server's data directory, which the app cannot claim.
        listOf(
            "thumb_7_1718438400000.jpg",
            "THUMB_7_1718438400000.jpg",
        ).forEach { assertFalse("$it is not an app export", SafExportName.isAppCreated(it)) }
    }

    @Test
    fun `only the app's own recordings are prunable`() {
        listOf(
            "REC_3_MOTION_20240615_083012.mkv",
            "REC_3_MOTION_20240615_083012_h264.mp4",
        ).forEach {
            assertTrue("$it may be removed", SafExportName.isPrunable(it))
            assertTrue("$it is app created", SafExportName.isAppCreated(it))
        }

        // Counted in the folder's usage, never deleted: the protection flag of a server clip lives on the
        // server, so a locked clip's archive copy would otherwise look like an ordinary candidate.
        assertTrue(SafExportName.isAppCreated("server_rec_5.mp4"))
        assertFalse(SafExportName.isPrunable("server_rec_5.mp4"))
    }

    @Test
    fun `leaves anything the user put in the folder alone`() {
        listOf(
            "holiday.mp4",
            "REC notes.txt",
            "my_REC_3_MOTION_20240615_083012.mkv",
            "REC_3_motion_20240615_083012.mkv",
            "REC_3_MOTION_20240615_083012 (1).mkv",
            "REC_3_MOTION_20240615_083012.mkv.bak",
            "thumbnails.txt",
            ".DS_Store",
        ).forEach {
            assertFalse("$it is not an app file", SafExportName.isAppCreated(it))
            assertFalse("$it is never prunable", SafExportName.isPrunable(it))
        }
    }

    @Test
    fun `reads the recording moment out of a clip name`() {
        assertEquals(
            epochOf(2024, Calendar.JUNE, 15, 8, 30, 12),
            SafExportName.recordedTimestampMillis("REC_3_MOTION_20240615_083012.mkv"),
        )
    }

    @Test
    fun `a transcode sorts at the same instant as the clip it came from`() {
        val source = SafExportName.recordedTimestampMillis("REC_3_MOTION_20240615_083012.mkv")
        assertEquals(source, SafExportName.recordedTimestampMillis("REC_3_MOTION_20240615_083012_h264.mp4"))
    }

    @Test
    fun `the oldest clip sorts first`() {
        val names = listOf(
            "REC_1_MOTION_20240616_000000.mkv",
            "REC_1_MOTION_20240614_235959.mkv",
            "REC_1_MOTION_20240615_120000_h264.mp4",
        )
        assertEquals(
            listOf(
                "REC_1_MOTION_20240614_235959.mkv",
                "REC_1_MOTION_20240615_120000_h264.mp4",
                "REC_1_MOTION_20240616_000000.mkv",
            ),
            names.sortedBy(SafExportName::recordedTimestampMillis),
        )
    }

    @Test
    fun `a name with no timestamp falls back to the document clock`() {
        listOf(
            "server_rec_5.mp4",
            "holiday.mp4",
            "REC_1_MOTION_20240615.mkv",
            "REC_1_MOTION_NOTADATE_083012.mkv",
        ).forEach { assertNull("$it names no instant", SafExportName.recordedTimestampMillis(it)) }
    }

    @Test
    fun `an impossible date is not rolled over into a real one`() {
        // Lenient parsing would read 31 February as early March and order the folder by a date that
        // never happened, which is exactly the mis-timed deletion this pass has to avoid.
        assertNull(SafExportName.recordedTimestampMillis("REC_1_MOTION_20240231_120000.mkv"))
    }

    private fun epochOf(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, second)
        }.timeInMillis
}
