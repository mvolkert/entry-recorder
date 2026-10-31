package io.github.mvolkert.entryrecorder.service

import android.util.Log
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.logIdentity
import io.github.mvolkert.entryrecorder.testing.testDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

/**
 * What the monitor queue has to guarantee: one event in the handler at a time, a burst delivered whole in
 * arrival order, and no event lost without a line saying so. The suspending fake handler stands in for the real
 * handler's HTTP and SIP work, which is the only reason two events ever overlapped when each got its own launch on
 * the multi-threaded IO dispatcher. Every specified log line is asserted through a planted [Timber.Tree], because
 * an unpinned forest silently drops every call the class makes.
 */
class MonitorEventQueueTest {

    private val dispatcher = StandardTestDispatcher()
    private val queueScope = CoroutineScope(dispatcher + SupervisorJob())

    private val doorbell = testDevice(id = 1L, name = "Doorbell")
    private val gate = testDevice(id = 2L, name = "Gate")

    /** What the queue logged, so that "the failure is recorded" is a checked claim and not a hope. */
    private val log = RecordingTree()
    private var logPlanted = false

    /** The queue's collector coroutine has no end, so it goes with the scope it was launched on. */
    @After
    fun tearDown() {
        queueScope.cancel()
        // Timber throws rather than shrugging at uprooting a tree it never planted, so only the tests that
        // planted one take it back out again.
        if (logPlanted) Timber.uproot(log)
    }

    /** Timber's forest is global, so the tree goes in explicitly in the tests that read it. */
    private fun plantLog() {
        Timber.plant(log)
        logPlanted = true
    }

    /**
     * The note the queue must have logged. JUnit's own `fail` returns void rather than Nothing, so the elvis form
     * would leave the caller with an `Any` and no `contains`; this keeps each assertion readable.
     */
    private fun loggedNote(level: Int, mentions: String): String =
        log.noteAt(level, mentions)
            ?: throw AssertionError("nothing logged at level $level mentions \"$mentions\"")

    /** Records what it was handed, optionally stuck on its first event to model slow handling. */
    private class FakeHandler {
        val handled = mutableListOf<IntercomEvent>()
        var blockFirstEvent: CompletableDeferred<Unit>? = null

        suspend fun handle(event: IntercomEvent) {
            if (handled.isEmpty()) blockFirstEvent?.await()
            handled += event
        }
    }

    @Test
    fun `a started event and the ended event after it are handled in that order`() = runTest(dispatcher) {
        val handler = FakeHandler().apply { blockFirstEvent = CompletableDeferred() }
        val queue = MonitorEventQueue(queueScope, handler::handle)
        val started = IntercomEvent.MotionOnDeviceStarted(doorbell)
        val ended = IntercomEvent.MotionOnDeviceEnded(doorbell)

        queue.submit(started)
        queue.submit(ended)
        advanceUntilIdle()

        // The end of the episode may not be scheduled while its start is still being handled: that ordering
        // is what decided whether the clip got truncated into the empty file the recorder discards.
        assertEquals(emptyList<IntercomEvent>(), handler.handled)

        handler.blockFirstEvent?.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(started, ended), handler.handled)
    }

    @Test
    fun `events from two devices are handled one at a time in arrival order`() = runTest(dispatcher) {
        val handler = FakeHandler().apply { blockFirstEvent = CompletableDeferred() }
        val queue = MonitorEventQueue(queueScope, handler::handle)
        val events = listOf(
            IntercomEvent.MotionOnDeviceStarted(doorbell),
            IntercomEvent.MotionOnDeviceStarted(gate),
            IntercomEvent.MotionOnDeviceEnded(doorbell),
            IntercomEvent.NoiseStarted(gate),
        )

        events.forEach { assertTrue(queue.submit(it)) }
        advanceUntilIdle()
        assertEquals(emptyList<IntercomEvent>(), handler.handled)

        handler.blockFirstEvent?.complete(Unit)
        advanceUntilIdle()

        assertEquals(events, handler.handled)
    }

    @Test
    fun `a burst faster than the handler is queued and delivered exactly once`() = runTest(dispatcher) {
        val handler = FakeHandler()
        val queue = MonitorEventQueue(queueScope, handler::handle)
        // Distinct payloads, so a duplicated or dropped delivery shows up in the equality below.
        val burst = (1L..20L).map { IntercomEvent.MotionOnDeviceStarted(doorbell.copy(name = "cam-$it")) }

        burst.forEach { queue.submit(it) }
        advanceUntilIdle()

        assertEquals(burst, handler.handled)
    }

    @Test
    fun `an event whose handler throws does not stop the events behind it`() = runTest(dispatcher) {
        val handled = mutableListOf<IntercomEvent>()
        val failing = IntercomEvent.MotionOnDeviceStarted(doorbell)
        val queue = MonitorEventQueue(queueScope) { event ->
            if (event === failing) error("snapshot client wedged")
            handled += event
        }
        val behind = IntercomEvent.MotionOnDeviceStarted(gate)

        queue.submit(failing)
        queue.submit(behind)
        advanceUntilIdle()

        // The collector is the only consumer, so losing it here would leave every later trigger unread while the
        // notification still claimed the monitor was running.
        assertEquals(listOf(behind), handled)
    }

    @Test
    fun `a handler failure is logged with the event identity and no credential`() = runTest(dispatcher) {
        plantLog()
        val withSecrets = doorbell.copy(password = "httptestpw", sipPassword = "siptestpw")
        val failing = IntercomEvent.MotionOnDeviceStarted(withSecrets)
        val queue = MonitorEventQueue(queueScope) { error("snapshot client wedged") }

        queue.submit(failing)
        advanceUntilIdle()

        val note = loggedNote(Log.ERROR, "Handler failed")
        assertTrue(note.contains(failing.logIdentity()))
        // Interpolating the event itself is what put both passwords into logcat before logIdentity existed.
        assertFalse(note.contains("httptestpw"))
        assertFalse(note.contains("siptestpw"))
    }

    @Test
    fun `a cancellation raised inside the handler belongs to one event, not to the queue`() = runTest(dispatcher) {
        plantLog()
        val handled = mutableListOf<IntercomEvent>()
        val timedOut = IntercomEvent.MotionOnDeviceStarted(doorbell)
        val behind = IntercomEvent.MotionOnDeviceStarted(gate)
        val queue = MonitorEventQueue(queueScope) { event ->
            if (event === timedOut) throw CancellationException("snapshot request timed out")
            handled += event
        }

        queue.submit(timedOut)
        queue.submit(behind)
        advanceUntilIdle()

        // The scope's job is a SupervisorJob, so a cancellation from inside the handler — a timeout, or an awaited
        // job that died on its own — is this event's ending and not the collector's. Swallowing it outright would
        // hide a handler that fails on every trigger, so it has to reach the log instead.
        assertEquals(listOf(behind), handled)
        val note = loggedNote(Log.WARN, "Handler cancelled")
        assertTrue(note.contains(timedOut.logIdentity()))
    }

    @Test
    fun `a cancelled scope still ends the collector and says so`() = runTest(dispatcher) {
        plantLog()
        val handled = mutableListOf<IntercomEvent>()
        val goingAway = IntercomEvent.MotionOnDeviceStarted(doorbell)
        val queue = MonitorEventQueue(queueScope) { event ->
            if (event === goingAway) {
                queueScope.cancel()
                throw CancellationException("the service is being destroyed")
            }
            handled += event
        }

        queue.submit(goingAway)
        queue.submit(IntercomEvent.MotionOnDeviceStarted(gate))
        advanceUntilIdle()

        // With the scope gone the collector must go with it, or it would keep consuming events for a dead service.
        assertEquals(emptyList<IntercomEvent>(), handled)
        loggedNote(Log.INFO, "collector stopped")
    }

    @Test
    fun `closing the queue reports the events its collector never read`() = runTest(dispatcher) {
        plantLog()
        val handler = FakeHandler().apply { blockFirstEvent = CompletableDeferred() }
        val queue = MonitorEventQueue(queueScope, handler::handle)
        val inHand = IntercomEvent.MotionOnDeviceStarted(doorbell)
        val queued = IntercomEvent.MotionOnDeviceEnded(doorbell)
        val alsoQueued = IntercomEvent.NoiseStarted(gate)

        queue.submit(inHand)
        queue.submit(queued)
        queue.submit(alsoQueued)
        advanceUntilIdle()

        queue.close()
        handler.blockFirstEvent?.complete(Unit)
        advanceUntilIdle()

        // Refusing later submits is only half the accounting: the collector died mid-handler, so whatever was
        // already buffered vanished as silently as it would have before close() existed.
        assertEquals(listOf(inHand), handler.handled)
        val note = loggedNote(Log.WARN, "queued events during teardown")
        assertTrue(note.contains("Dropped 2"))
        assertTrue(note.contains(queued.logIdentity()))
        assertTrue(note.contains(alsoQueued.logIdentity()))
    }

    @Test
    fun `a closed queue refuses a submit instead of accepting an event nobody reads`() = runTest(dispatcher) {
        plantLog()
        val handler = FakeHandler()
        val queue = MonitorEventQueue(queueScope, handler::handle)
        val refused = IntercomEvent.MotionOnDeviceStarted(doorbell)
        queue.close()

        assertFalse(queue.submit(refused))
        advanceUntilIdle()

        assertEquals(emptyList<IntercomEvent>(), handler.handled)
        val note = loggedNote(Log.WARN, "the monitor queue is closed")
        assertTrue(note.contains(refused.logIdentity()))
    }

    /**
     * A [Timber.Tree] that keeps the notes it was handed. Uprooted in [tearDown], because planting into the global
     * forest would otherwise leak into every other test in the module.
     */
    private class RecordingTree : Timber.Tree() {
        private val notes = mutableListOf<Pair<Int, String>>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            notes += priority to message
        }

        /** The one note at [level] mentioning [text], or null when the queue never logged it. */
        fun noteAt(level: Int, text: String): String? =
            notes.singleOrNull { it.first == level && it.second.contains(text) }?.second
    }
}
