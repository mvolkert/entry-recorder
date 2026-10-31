package io.github.mvolkert.entryrecorder.domain.device

import io.github.mvolkert.entryrecorder.data.model.ConnectionCapability
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.domain.model.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An in-app motion analyzer is rebuilt only when the configuration the analysis reads changes, so it outlives every
 * other edit to its device — and it stamps its events with the row that existed when it was built. These tests pin
 * the substitution that fixes that: the event keeps its kind and everything the device reported, and carries only a
 * different row.
 */
class IntercomEventCurrentRowTest {

    private val stale = Device(
        id = 7L,
        name = "Front Door",
        ipAddress = "192.168.1.50",
        wakeOnMotion = false,
    )

    private val current = stale.copy(name = "Gate camera", wakeOnMotion = true)

    private fun eventsOn(row: Device): List<IntercomEvent> = listOf(
        IntercomEvent.MotionStarted(row, timestamp = 11L),
        IntercomEvent.MotionEnded(row, timestamp = 12L),
        IntercomEvent.NoiseStarted(row, timestamp = 13L),
        IntercomEvent.NoiseEnded(row, timestamp = 14L),
        IntercomEvent.MotionOnDeviceStarted(row, timestamp = 15L),
        IntercomEvent.MotionOnDeviceEnded(row, timestamp = 16L),
        IntercomEvent.DoorbellRung(row, callerNumber = "+4912345", timestamp = 17L),
        IntercomEvent.ConnectionState(
            row,
            ConnectionQuality.OFFLINE,
            message = "no route",
            capability = ConnectionCapability.SNAPSHOT,
            timestamp = 18L,
        ),
        IntercomEvent.Error(row, IllegalStateException("endpoint dead")),
    )

    @Test
    fun `every event keeps its kind and its own fields when the row is replaced`() {
        for (event in eventsOn(stale)) {
            val resolved = event.withCurrentRow(current)

            assertEquals(event::class, resolved::class)
            assertEquals(current, resolved.device)
            assertNotSame(event, resolved)
        }

        val ring = eventsOn(stale).filterIsInstance<IntercomEvent.DoorbellRung>().single()
            .withCurrentRow(current) as IntercomEvent.DoorbellRung
        assertEquals("+4912345", ring.callerNumber)
        assertEquals(17L, ring.timestamp)

        val health = eventsOn(stale).filterIsInstance<IntercomEvent.ConnectionState>().single()
            .withCurrentRow(current) as IntercomEvent.ConnectionState
        assertEquals(ConnectionQuality.OFFLINE, health.quality)
        assertEquals("no route", health.message)
        assertEquals(ConnectionCapability.SNAPSHOT, health.capability)
        assertEquals(18L, health.timestamp)

        val failure = eventsOn(stale).filterIsInstance<IntercomEvent.Error>().single()
            .withCurrentRow(current) as IntercomEvent.Error
        assertEquals("endpoint dead", failure.error.message)
    }

    @Test
    fun `a device that is no longer monitored keeps the row it arrived with`() {
        val event = IntercomEvent.MotionOnDeviceStarted(stale)

        assertSame(event, event.withCurrentRow(null))
        assertSame(event, event.withCurrentRow(stale))
    }

    @Test
    fun `a surviving analyzer's motion event wakes the screen on the edited setting`() {
        // TriggerEpisodeRunner reads the alert flags off the event's own row, so a stale row here is a motion alert
        // that ignores a switch the user has since flipped.
        val started = IntercomEvent.MotionOnDeviceStarted(stale)

        val resolved = started.withCurrentRow(current) as IntercomEvent.MotionOnDeviceStarted

        assertTrue(resolved.device.wakeOnMotion)
        assertFalse(started.device.wakeOnMotion)
    }
}
