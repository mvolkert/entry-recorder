package io.github.mvolkert.entryrecorder.video

import io.github.mvolkert.entryrecorder.data.model.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Covers the trigger-folding policy of a running clip that Phase 3 made atomic: the ranking rule (a more
 * significant trigger takes over the primary tag and demotes the old one into the folded set, a less
 * significant one is only added to the set, a repeat of the primary is rejected), and — the whole point of the
 * `@Synchronized` fold — that concurrent folds never lose a tag or leave the primary below the most
 * significant trigger seen.
 */
class MutableTriggersFoldTest {

    @Test
    fun `a more significant trigger takes over the primary and demotes the old one`() {
        val triggers = LocalCaptureLoop.MutableTriggers(EventType.MOTION)
        assertTrue(triggers.fold(EventType.RING))
        assertEquals(EventType.RING, triggers.primary)
        assertEquals(listOf(EventType.MOTION), triggers.foldedList())
    }

    @Test
    fun `a less significant trigger is folded in without changing the primary`() {
        val triggers = LocalCaptureLoop.MutableTriggers(EventType.MOTION)
        assertTrue(triggers.fold(EventType.NOISE))
        assertEquals(EventType.MOTION, triggers.primary)
        assertEquals(listOf(EventType.NOISE), triggers.foldedList())
    }

    @Test
    fun `refolding the current primary is rejected`() {
        val triggers = LocalCaptureLoop.MutableTriggers(EventType.MOTION)
        assertFalse(triggers.fold(EventType.MOTION))
        assertEquals(emptyList<EventType>(), triggers.foldedList())
    }

    @Test
    fun `folded triggers list is ordered most significant first`() {
        val triggers = LocalCaptureLoop.MutableTriggers(EventType.RING)
        triggers.fold(EventType.NOISE)
        triggers.fold(EventType.MOTION)
        assertEquals(listOf(EventType.MOTION, EventType.NOISE), triggers.foldedList())
    }

    /**
     * Hammers one instance from several threads with a fixed pool of trigger types. Whatever the interleaving,
     * the union of the primary and the folded set must equal every distinct type fed in (no tag lost to a race),
     * and the primary must be the single most significant one. The atomic fold guarantees both; an unsynchronized
     * read-modify-write could drop a demoted primary.
     */
    @Test
    fun `concurrent folds never lose a tag and keep the most significant primary`() {
        val types = listOf(EventType.MANUAL, EventType.NOISE, EventType.MOTION, EventType.RING)
        val triggers = LocalCaptureLoop.MutableTriggers(EventType.MANUAL)
        val threadCount = 8
        val iterations = 5_000
        val pool = Executors.newFixedThreadPool(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        repeat(threadCount) {
            pool.execute {
                start.await()
                repeat(iterations) { triggers.fold(types.random()) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(EventType.RING, triggers.primary)
        val seen = buildSet {
            add(triggers.primary)
            addAll(triggers.foldedList())
        }
        assertEquals(types.toSet(), seen)
    }
}
