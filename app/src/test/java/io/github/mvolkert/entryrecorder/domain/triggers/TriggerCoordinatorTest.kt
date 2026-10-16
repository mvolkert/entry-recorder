package io.github.mvolkert.entryrecorder.domain.triggers

import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.model.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure trigger-routing policy the monitor service delegates to, driven by a fake clock so the
 * debounce window is tested without sleeping: duplicate-ring collapsing, per-device isolation of that window,
 * the post-record / hold duration decisions, and the honest zero-second answer for an unrecognised event.
 */
class TriggerCoordinatorTest {

    private class FakeClock(start: Long = 1_000_000L) : TimeProvider {
        var now: Long = start
        override fun currentTimeMillis(): Long = now
    }

    private val clock = FakeClock()
    private val coordinator = TriggerCoordinator(clock)

    private val device = Device(
        id = 1L,
        name = "Front Door",
        ipAddress = "192.168.1.50",
        motionPostRecordSeconds = 20,
        noisePostRecordSeconds = 15,
        ringRecordSeconds = 60,
    )

    @Test
    fun `first ring is handled`() {
        assertTrue(coordinator.shouldHandleRing(deviceId = 1L))
    }

    @Test
    fun `ring inside the debounce window collapses as a duplicate`() {
        assertTrue(coordinator.shouldHandleRing(1L))
        clock.now += TriggerCoordinator.RING_DEBOUNCE_MS - 1
        assertFalse(coordinator.shouldHandleRing(1L))
    }

    @Test
    fun `ring at or past the debounce boundary is a fresh press`() {
        assertTrue(coordinator.shouldHandleRing(1L))
        clock.now += TriggerCoordinator.RING_DEBOUNCE_MS
        assertTrue(coordinator.shouldHandleRing(1L))
    }

    @Test
    fun `debounce is tracked per device`() {
        assertTrue(coordinator.shouldHandleRing(1L))
        clock.now += 100
        // A different device's ring is unaffected by device 1's stamp.
        assertTrue(coordinator.shouldHandleRing(2L))
    }

    @Test
    fun `forgetting a device resets its debounce stamp`() {
        assertTrue(coordinator.shouldHandleRing(1L))
        clock.now += 100
        coordinator.forgetDevice(1L)
        // Fresh stamp after forget, so the very next ring is handled again.
        assertTrue(coordinator.shouldHandleRing(1L))
    }

    @Test
    fun `reset clears every device stamp`() {
        assertTrue(coordinator.shouldHandleRing(1L))
        assertTrue(coordinator.shouldHandleRing(2L))
        clock.now += 100
        coordinator.reset()
        assertTrue(coordinator.shouldHandleRing(1L))
        assertTrue(coordinator.shouldHandleRing(2L))
    }

    @Test
    fun `post-record seconds read the matching device field per trigger`() {
        assertEquals(20, coordinator.postRecordSeconds(device, EventType.MOTION))
        assertEquals(15, coordinator.postRecordSeconds(device, EventType.NOISE))
        assertEquals(60, coordinator.postRecordSeconds(device, EventType.RING))
        assertEquals(60, coordinator.postRecordSeconds(device, EventType.MANUAL))
    }

    @Test
    fun `an unrecognised event asks for no post-record buffer`() {
        assertEquals(0, coordinator.postRecordSeconds(device, EventType.UNKNOWN))
    }

    @Test
    fun `trigger record duration adds the headroom to the post-record buffer`() {
        assertEquals(
            20 + TriggerCoordinator.TRIGGER_RECORD_HEADROOM_SECONDS,
            coordinator.triggerRecordDurationSeconds(device, EventType.MOTION),
        )
        assertEquals(
            15 + TriggerCoordinator.TRIGGER_RECORD_HEADROOM_SECONDS,
            coordinator.triggerRecordDurationSeconds(device, EventType.NOISE),
        )
    }

    @Test
    fun `ring record duration is the device ring length`() {
        assertEquals(60, coordinator.ringRecordDurationSeconds(device))
    }

    @Test
    fun `lock hold spans the clip plus the safety margin`() {
        assertEquals(
            60 * 1000L + TriggerCoordinator.TRIGGER_LOCK_MARGIN_MS,
            coordinator.lockHoldDurationMs(recordSeconds = 60),
        )
    }
}
