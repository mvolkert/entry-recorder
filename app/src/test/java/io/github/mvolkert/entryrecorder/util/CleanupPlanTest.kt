package io.github.mvolkert.entryrecorder.util

import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both cleanup passes end in a delete call, so the decision that leads there gets its own tests: what a
 * quota is allowed to touch, what it is measured over, in which order candidates are picked and when the
 * picking stops. Everything here runs on plain names and byte counts instead of a live content provider or
 * an app database.
 *
 * The bound a pass aims for is measured over the bytes that pass may actually remove, which is what the
 * `asks for nothing` cases below pin down: a folder dominated by protected clips or server downloads must
 * not turn into an order to delete everything that is not.
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
            quotaMb = 100,
        )
        assertEquals(listOf("REC_1_MOTION_20240616_083012.mkv"), plan.deletions.map { it.displayName })
        assertEquals(300 * mb, plan.deletableBytes)
    }

    @Test
    fun `protected footage is never paid for with the clips that are not protected`() {
        // 900 MB of app files against a 400 MB quota, but 600 MB of it is the two copies of a locked clip.
        // Measuring the target over all 900 MB would delete the third file and, after the next export, the
        // one after it — an endless purge the quota was never meant to authorize.
        val plan = CleanupPlan.external(
            entries = listOf(
                entry("REC_1_MOTION_20240615_083012.mkv", 300),
                entry("REC_1_MOTION_20240615_083012_h264.mp4", 300),
                entry("REC_1_MOTION_20240616_083012.mkv", 300),
            ),
            protectedNames = CleanupPlan.protectedExportNames(listOf("/recordings/REC_1_MOTION_20240615_083012.mkv")),
            quotaMb = 400,
        )
        assertEquals(300 * mb, plan.deletableBytes)
        assertEquals(0L, plan.bytesToFree)
        assertTrue(plan.deletions.isEmpty())
    }

    @Test
    fun `server downloads are never candidates for deletion`() {
        // The protection flag of a server clip lives on the server, so its archive copy cannot be trusted
        // to be deletable just because this app downloaded it into the folder.
        val plan = CleanupPlan.external(
            entries = listOf(
                entry("server_rec_5.mp4", 800),
                entry("REC_1_MOTION_20240615_083012.mkv", 300),
            ),
            protectedNames = emptySet(),
            quotaMb = 100,
        )
        assertEquals(listOf("REC_1_MOTION_20240615_083012.mkv"), plan.deletions.map { it.displayName })
        assertEquals(300 * mb, plan.deletableBytes)
        assertFalse("a download is not prunable", plan.deletions.any { it.displayName == "server_rec_5.mp4" })
    }

    @Test
    fun `a folder of nothing but downloads asks for nothing`() {
        val plan = CleanupPlan.external(
            entries = listOf(entry("server_rec_5.mp4", 800)),
            protectedNames = emptySet(),
            quotaMb = 100,
        )
        assertEquals(0L, plan.deletableBytes)
        assertEquals(0L, plan.bytesToFree)
        assertTrue(plan.deletions.isEmpty())
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
            quotaMb = 100,
        )
        assertEquals(300 * mb, plan.deletableBytes)
        assertEquals(listOf("REC_1_MOTION_20240615_083012.mkv"), plan.deletions.map { it.displayName })
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
        // 1200 MB of prunable files against a 400 MB limit asks for 900 MB back, so three clips go and the
        // newest stays even though a fourth candidate is listed after it.
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
        assertEquals(1200 * mb, plan.deletableBytes)
        assertEquals(900 * mb, plan.bytesToFree)
        assertEquals(3, plan.deletions.size)
        assertEquals(900 * mb, plan.reclaimedBytes)
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
            assertEquals(600 * mb, plan.deletableBytes)
            assertEquals(0L, plan.bytesToFree)
            assertTrue("quota $quota must delete nothing", plan.deletions.isEmpty())
        }
    }

    @Test
    fun `the internal pass frees oldest-first down to one step under its quota`() {
        // The caller hands these over oldest-first, which is what the DAO's ORDER BY timestamp guarantees.
        val plan = CleanupPlan.internal(
            candidates = listOf(candidate(id = 1, videoMb = 300, thumbnailMb = 0), candidate(2, 300, 0), candidate(3, 300, 0)),
            quotaMb = 400,
        )
        assertEquals(600 * mb, plan.bytesToFree)
        assertEquals(listOf(1L, 2L), plan.deletions.map { it.recording.id })
        assertEquals(600 * mb, plan.reclaimedBytes)
    }

    @Test
    fun `an internal candidate is credited with everything deleting it takes away`() {
        // The worker adds the clip's thumbnail because `deleteRecording` removes both files; a pass that
        // credited only the video would still believe it was short after every deletion and keep going.
        val plan = CleanupPlan.internal(
            candidates = listOf(candidate(id = 1, videoMb = 100, thumbnailMb = 100)),
            quotaMb = 100,
        )
        assertEquals(200 * mb, plan.deletableBytes)
        assertEquals(150 * mb, plan.bytesToFree)
        assertEquals(200 * mb, plan.reclaimedBytes)
        assertEquals(listOf(1L), plan.deletions.map { it.recording.id })
    }

    @Test
    fun `the internal pass stops at its own candidates`() {
        // Protected clips never reach the planner, so a quota that only the protected half of the archive
        // could answer for asks nothing of the clips that are still there.
        val plan = CleanupPlan.internal(
            candidates = listOf(candidate(id = 1, videoMb = 150, thumbnailMb = 0), candidate(2, 150, 0)),
            quotaMb = 400,
        )
        assertEquals(0L, plan.bytesToFree)
        assertTrue(plan.deletions.isEmpty())
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

    private fun candidate(id: Long, videoMb: Long, thumbnailMb: Long) = InternalCandidate(
        recording = RecordingEntity(
            id = id,
            deviceId = 1L,
            deviceName = "Doorbell",
            eventType = EventType.MOTION,
            filePath = "/files/recordings/REC_1_MOTION_202406${id}_083012.mkv",
            fileSizeBytes = videoMb * mb,
        ),
        reclaimableBytes = (videoMb + thumbnailMb) * mb,
    )
}
