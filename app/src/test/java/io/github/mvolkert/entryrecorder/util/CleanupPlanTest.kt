package io.github.mvolkert.entryrecorder.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The external pass ends in `DocumentsContract.deleteDocument`, so the decision that leads there gets its
 * own tests: which files the folder's usage is measured over, which of those are candidates at all, in
 * which order they are picked, and when the picking stops. Everything here runs on plain names and byte
 * counts instead of a live content provider.
 */
class CleanupPlanTest {

    private val mb = 1024L * 1024L

    @Test
    fun `a protected clip keeps its export and its transcode`() {
        val protectedNames = CleanupPlan.protectedExportNames(
            listOf("/data/user/0/io.github.mvolkert.entryrecorder/files/recordings/REC_1_MOTION_20240615_083012.mkv")
        )
        val plan = CleanupPlan.external(
            entries = listOf(
                entry("REC_1_MOTION_20240615_083012.mkv", 300),
                entry("REC_1_MOTION_20240615_083012_h264.mp4", 300),
                entry("REC_1_MOTION_20240616_083012.mkv", 300),
            ),
            protectedNames = protectedNames,
            quotaMb = 400,
        )
        assertEquals(listOf("REC_1_MOTION_20240616_083012.mkv"), plan.deletions.map { it.displayName })
        assertEquals(900 * mb, plan.appBytes)
    }

    @Test
    fun `server downloads are measured but never removed`() {
        // The protection flag of a server clip lives on the server, so its archive copy cannot be trusted
        // to be deletable just because the app downloaded it into the folder.
        val plan = CleanupPlan.external(
            entries = listOf(
                entry("server_rec_5.mp4", 800),
                entry("REC_1_MOTION_20240615_083012.mkv", 300),
            ),
            protectedNames = emptySet(),
            quotaMb = 400,
        )
        assertEquals(1100 * mb, plan.appBytes)
        assertEquals(listOf("REC_1_MOTION_20240615_083012.mkv"), plan.deletions.map { it.displayName })
        assertFalse(plan.quotaReachable)
    }

    @Test
    fun `files this app did not write are invisible`() {
        val plan = CleanupPlan.external(
            entries = listOf(
                entry("holiday.mp4", 5_000),
                entry("THUMB_1_1718438400000.jpg", 100),
                entry("REC_1_MOTION_20240615_083012.mkv", 300),
            ),
            protectedNames = emptySet(),
            quotaMb = 400,
        )
        assertEquals(300 * mb, plan.appBytes)
        assertEquals(0L, plan.bytesToFree)
        assertTrue(plan.deletions.isEmpty())
    }

    @Test
    fun `the oldest recording goes first`() {
        val plan = CleanupPlan.external(
            entries = listOf(
                entry("REC_1_MOTION_20240616_000000.mkv", 300),
                entry("REC_1_MOTION_20240614_000000.mkv", 300),
                entry("REC_1_MOTION_20240615_000000.mkv", 300),
            ),
            protectedNames = emptySet(),
            quotaMb = 400,
        )
        assertEquals(
            listOf("REC_1_MOTION_20240614_000000.mkv", "REC_1_MOTION_20240615_000000.mkv"),
            plan.deletions.map { it.displayName },
        )
    }

    @Test
    fun `a name with an impossible date sorts by the provider clock`() {
        // 31 February parses to nothing, so the document's own timestamp is the only age left to judge by.
        val plan = CleanupPlan.external(
            entries = listOf(
                ExportEntry("REC_1_MOTION_20240231_120000.mkv", 300 * mb, lastModifiedMillis = 1_000L),
                entry("REC_1_MOTION_20240614_000000.mkv", 300),
            ),
            protectedNames = emptySet(),
            quotaMb = 250,
        )
        assertEquals(
            listOf("REC_1_MOTION_20240231_120000.mkv", "REC_1_MOTION_20240614_000000.mkv"),
            plan.deletions.map { it.displayName },
        )
    }

    @Test
    fun `the pick stops as soon as the quota is satisfied`() {
        // 1200 MB used against a 400 MB limit asks for 900 MB back, so three clips go and the newest stays.
        val plan = CleanupPlan.external(
            entries = listOf(
                entry("REC_1_MOTION_20240614_000000.mkv", 300),
                entry("REC_1_MOTION_20240615_000000.mkv", 300),
                entry("REC_1_MOTION_20240616_000000.mkv", 300),
                entry("REC_1_MOTION_20240617_000000.mkv", 300),
            ),
            protectedNames = emptySet(),
            quotaMb = 400,
        )
        assertEquals(900 * mb, plan.bytesToFree)
        assertEquals(3, plan.deletions.size)
        assertEquals(900 * mb, plan.reclaimedBytes)
        assertTrue(plan.quotaReachable)
    }

    @Test
    fun `churn smaller than one step deletes nothing`() {
        val plan = CleanupPlan.external(
            entries = listOf(entry("REC_1_MOTION_20240615_083012.mkv", 300)),
            protectedNames = emptySet(),
            quotaMb = 250,
        )
        assertEquals(0L, plan.bytesToFree)
        assertTrue(plan.deletions.isEmpty())
    }

    @Test
    fun `an unusable quota never becomes a delete order`() {
        listOf(0L, -1L, CleanupPolicy.MAX_QUOTA_MB + 1024L).forEach { quota ->
            val plan = CleanupPlan.external(
                entries = listOf(
                    entry("REC_1_MOTION_20240615_083012.mkv", 300),
                    entry("REC_1_MOTION_20240616_083012.mkv", 300),
                ),
                protectedNames = emptySet(),
                quotaMb = quota,
            )
            assertEquals(600 * mb, plan.appBytes)
            assertEquals(0L, plan.bytesToFree)
            assertTrue("quota $quota must delete nothing", plan.deletions.isEmpty())
        }
    }

    @Test
    fun `protected names carry both the clip and its derived transcode`() {
        val names = CleanupPlan.protectedExportNames(
            listOf(
                "/recordings/REC_9_MANUAL_20240101_010101.mkv",
                "REC_8_SIP_20240102_020202.mp4",
            )
        )
        assertEquals(
            setOf(
                "REC_9_MANUAL_20240101_010101.mkv",
                "REC_9_MANUAL_20240101_010101_h264.mp4",
                "REC_8_SIP_20240102_020202.mp4",
                "REC_8_SIP_20240102_020202_h264.mp4",
            ),
            names,
        )
    }

    private fun entry(name: String, sizeMb: Long) =
        ExportEntry(name, sizeBytes = sizeMb * mb, lastModifiedMillis = 0L)
}
