package io.github.mvolkert.entryrecorder.service

import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.testing.testDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the monitor queue has to guarantee: one event in the handler at a time, and a burst delivered whole in
 * arrival order. The suspending fake handler stands in for the real handler's HTTP and SIP work, which is the
 * only reason two events ever overlapped when each got its own launch on the multi-threaded IO dispatcher.
 */
class MonitorEventQueueTest {

    private val dispatcher = StandardTestDispatcher()
    private val queueScope = CoroutineScope(dispatcher + SupervisorJob())

    private val doorbell = testDevice(id = 1L, name = "Doorbell")
    private val gate = testDevice(id = 2L, name = "Gate")

    /** The queue's collector coroutine has no end, so it goes with the scope it was launched on. */
    @After
    fun tearDown() {
        queueScope.cancel()
    }

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
}
