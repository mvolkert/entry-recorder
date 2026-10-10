package io.github.mvolkert.entryrecorder.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The purge contract shared by the settings steppers and the cleanup worker. Everything is in 100 MB
 * units on purpose: a quota the user can dial in has to be the quota the worker aims for, and both halves
 * do their arithmetic in bytes from a `Long` megabyte value, which is where an `Int` intermediate would
 * silently truncate past 2 GB.
 */
class CleanupPolicyTest {

    private val mb = 1024L * 1024L

    @Test
    fun `a step is 100 MB and the range spans 100 MB to 100 GB`() {
        assertEquals(100L, CleanupPolicy.STEP_MB)
        assertTrue(CleanupPolicy.inRange(CleanupPolicy.MIN_QUOTA_MB))
        assertTrue(CleanupPolicy.inRange(CleanupPolicy.MAX_QUOTA_MB))
        assertFalse(CleanupPolicy.inRange(CleanupPolicy.MIN_QUOTA_MB - 1))
        assertFalse(CleanupPolicy.inRange(CleanupPolicy.MAX_QUOTA_MB + 1))
    }

    @Test
    fun `steppers move one step and stop at both ends`() {
        assertEquals(1100L, CleanupPolicy.nextQuotaMb(1000L, 1))
        assertEquals(900L, CleanupPolicy.nextQuotaMb(1000L, -1))
        assertEquals(CleanupPolicy.MAX_QUOTA_MB, CleanupPolicy.nextQuotaMb(CleanupPolicy.MAX_QUOTA_MB, 1))
        assertEquals(CleanupPolicy.MIN_QUOTA_MB, CleanupPolicy.nextQuotaMb(CleanupPolicy.MIN_QUOTA_MB, -1))
    }

    @Test
    fun `usage at or under quota frees nothing`() {
        val quota = 1000L
        assertEquals(0L, CleanupPolicy.bytesToFree(quota * mb, quota))
        assertEquals(0L, CleanupPolicy.bytesToFree((quota - 1) * mb, quota))
    }

    @Test
    fun `overflow smaller than one step does not start a pass`() {
        // A daily pass that deletes a clip over a megabyte of churn would be worse than being slightly over.
        val quota = 1000L
        assertEquals(0L, CleanupPolicy.bytesToFree(quota * mb + 1L * mb, quota))
        assertEquals(0L, CleanupPolicy.bytesToFree(quota * mb + 99L * mb, quota))
    }

    @Test
    fun `a full step over quota frees down to one step under it`() {
        val quota = 1000L
        // Used 1100 MB, target 900 MB: the 100 MB headroom keeps the next clip from overflowing at once.
        assertEquals(200L * mb, CleanupPolicy.bytesToFree(quota * mb + 100L * mb, quota))
    }

    @Test
    fun `byte math stays in long past the int range`() {
        // 100 GB quota: 102 400 MB is 107 374 182 400 bytes, which no Int could hold.
        val quota = CleanupPolicy.MAX_QUOTA_MB
        assertEquals(200L * mb, CleanupPolicy.bytesToFree(quota * mb + 100L * mb, quota))
    }

    @Test
    fun `the smallest quota still leaves half of it usable`() {
        // A one-step-below target on a 100 MB quota would be zero, i.e. "delete everything".
        assertEquals(250L * mb, CleanupPolicy.bytesToFree(300L * mb, 100L))
    }

    @Test
    fun `a quota outside the range is inert rather than a purge order`() {
        // A stored 0 is reachable from a backup written before the field existed, and a value above the cap
        // from the days when the old stepper had no maximum. Neither may read as "delete everything".
        assertEquals(0L, CleanupPolicy.bytesToFree(500L * mb, 0L))
        assertEquals(0L, CleanupPolicy.bytesToFree(500L * mb, -1L))
        assertEquals(0L, CleanupPolicy.bytesToFree(500L * mb, CleanupPolicy.MAX_QUOTA_MB + 1024L))
    }

    @Test
    fun `whole gigabytes only when the value is clean`() {
        assertEquals(10L, CleanupPolicy.wholeGigabytes(10_240L))
        assertNull(CleanupPolicy.wholeGigabytes(2500L))
        assertNull(CleanupPolicy.wholeGigabytes(100L))
    }
}
