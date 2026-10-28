package io.github.mvolkert.entryrecorder.domain.triggers

import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.model.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `no configured ceiling leaves armed durations alone`() {
        assertNull(TriggerCoordinator.recordCeilingSeconds(device, EventType.MOTION))
        assertNull(TriggerCoordinator.recordCeilingSeconds(device, EventType.NOISE))
        assertNull(TriggerCoordinator.recordCeilingSeconds(device, EventType.RING))
        assertEquals(50, coordinator.triggerRecordDurationSeconds(device, EventType.MOTION))
        assertEquals(60, coordinator.ringRecordDurationSeconds(device))
    }

    @Test
    fun `a motion frame ceiling converts through the snapshot rate and caps the armed length`() {
        // 5 fps configured: 30 frames is 6 s, which is well under the 50 s the trigger arms by default.
        val capped = device.copy(motionMaxFrames = 30)
        assertEquals(6, TriggerCoordinator.recordCeilingSeconds(capped, EventType.MOTION))
        assertEquals(6, coordinator.triggerRecordDurationSeconds(capped, EventType.MOTION))
        assertEquals(20, coordinator.postRecordSeconds(capped, EventType.MOTION))
    }

    @Test
    fun `a partial frame second rounds the ceiling up instead of dropping the last frame`() {
        // 26 frames at 5 fps is 5.2 s; truncating would cut the clip one frame short of its own limit.
        assertEquals(6, TriggerCoordinator.recordCeilingSeconds(device.copy(motionMaxFrames = 26), EventType.MOTION))
        assertEquals(3, TriggerCoordinator.recordCeilingSeconds(device.copy(noiseMaxFrames = 11), EventType.NOISE))
        // A sub-second ceiling still bounds a clip at a second; it never rounds down to "record nothing".
        assertEquals(1, TriggerCoordinator.recordCeilingSeconds(device.copy(motionMaxFrames = 1), EventType.MOTION))
    }

    @Test
    fun `the noise ceiling reads the noise field, not the motion one`() {
        val capped = device.copy(noiseMaxFrames = 50)
        assertEquals(10, TriggerCoordinator.recordCeilingSeconds(capped, EventType.NOISE))
        // 50 frames at 5 fps is 10 s, under the 45 s the noise trigger arms with its 15 s post-record.
        assertEquals(10, coordinator.triggerRecordDurationSeconds(capped, EventType.NOISE))
        assertNull(TriggerCoordinator.recordCeilingSeconds(capped, EventType.MOTION))
    }

    @Test
    fun `ring max minutes caps a ring clip that would otherwise run longer`() {
        val capped = device.copy(ringMaxMinutes = 1)
        assertEquals(60, TriggerCoordinator.recordCeilingSeconds(capped, EventType.RING))
        assertEquals(60, coordinator.ringRecordDurationSeconds(capped))
        // A ring shorter than its ceiling keeps its own length.
        assertEquals(30, coordinator.ringRecordDurationSeconds(device.copy(ringRecordSeconds = 30, ringMaxMinutes = 2)))
    }

    @Test
    fun `manual and unrecognised triggers have no ceiling`() {
        val capped = device.copy(motionMaxFrames = 10, ringMaxMinutes = 10)
        assertNull(TriggerCoordinator.recordCeilingSeconds(capped, EventType.MANUAL))
        assertNull(TriggerCoordinator.recordCeilingSeconds(capped, EventType.UNKNOWN))
    }

    @Test
    fun `lock hold spans the clip plus the safety margin`() {
        assertEquals(
            60 * 1000L + TriggerCoordinator.TRIGGER_LOCK_MARGIN_MS,
            coordinator.lockHoldDurationMs(recordSeconds = 60),
        )
    }
}
