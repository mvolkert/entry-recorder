package io.github.mvolkert.entryrecorder.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared connection-loss wording. Only the transition lines matter to an operator grepping a stream, so
 * the format and the duration rendering are pinned here rather than re-derived in four transport classes.
 */
class ConnectionLogTest {

    @Test
    fun `lost names the source, the link and the reason`() {
        assertEquals(
            "snapshot lost: Front Door (10 polls in a row without a frame)",
            ConnectionLog.lost(ConnectionSource.Snapshot, "Front Door", "10 polls in a row without a frame"),
        )
        assertEquals(
            "python-server lost: http://192.168.1.5:8000 (connect timeout)",
            ConnectionLog.lost(ConnectionSource.PythonServer, "http://192.168.1.5:8000", "connect timeout"),
        )
    }

    @Test
    fun `recovered carries the outage duration when one was recorded`() {
        assertEquals(
            "snapshot recovered: Front Door (down 45s)",
            ConnectionLog.recovered(ConnectionSource.Snapshot, "Front Door", 45_000L),
        )
        assertEquals(
            "camera-events recovered: Gate (down 2m 5s)",
            ConnectionLog.recovered(ConnectionSource.CameraEvents, "Gate", 125_000L),
        )
    }

    @Test
    fun `recovered omits the duration clause when no loss timestamp was kept`() {
        assertEquals(
            "sip-registration recovered: Office",
            ConnectionLog.recovered(ConnectionSource.SipRegistration, "Office"),
        )
        assertEquals(
            "sip-registration recovered: Office",
            ConnectionLog.recovered(ConnectionSource.SipRegistration, "Office", null),
        )
    }

    @Test
    fun `durations switch from seconds to minutes at sixty`() {
        assertEquals("0s", ConnectionLog.formatDuration(0L))
        assertEquals("59s", ConnectionLog.formatDuration(59_999L))
        assertEquals("1m 0s", ConnectionLog.formatDuration(60_000L))
        assertEquals("65m 0s", ConnectionLog.formatDuration(3_900_000L))
        // A clock that went backwards never reports a negative outage.
        assertEquals("0s", ConnectionLog.formatDuration(-1L))
    }

    @Test
    fun `a loss edge fires once and the recovery edge carries the outage duration`() {
        val tracker = ConnectionEdgeTracker()
        val key = "SNAPSHOT|7"
        // First down read announces the loss.
        assertEquals(
            ConnectionEdge.Lost("10 polls without a frame"),
            tracker.observe(key, online = false, reason = "10 polls without a frame", nowMs = 1_000L),
        )
        // A later down read is the same outage, not a second loss line.
        assertEquals(ConnectionEdge.None, tracker.observe(key, online = false, reason = "still down", nowMs = 4_000L))
        // The first up read reports recovery, measured from when the link first went down.
        assertEquals(
            ConnectionEdge.Recovered(9_000L),
            tracker.observe(key, online = true, reason = "", nowMs = 10_000L),
        )
        // A further up read is not a second recovery.
        assertEquals(ConnectionEdge.None, tracker.observe(key, online = true, reason = "", nowMs = 11_000L))
    }

    @Test
    fun `a startup online notice that never followed a loss is not a recovery`() {
        val tracker = ConnectionEdgeTracker()
        assertEquals(
            ConnectionEdge.None,
            tracker.observe("DEVICE_EVENTS|3", online = true, reason = "RTSP Ready", nowMs = 1_000L),
        )
    }

    @Test
    fun `distinct keys are tracked independently`() {
        val tracker = ConnectionEdgeTracker()
        assertEquals(ConnectionEdge.Lost("a"), tracker.observe("SNAPSHOT|1", false, "a", 1_000L))
        assertEquals(ConnectionEdge.Lost("b"), tracker.observe("DEVICE_EVENTS|1", false, "b", 1_000L))
        // Recovering the snapshot key leaves the event key's own outage clock untouched.
        assertEquals(ConnectionEdge.Recovered(1_000L), tracker.observe("SNAPSHOT|1", true, "", 2_000L))
        // The event link was down since 1_000, so its first up read is its own recovery at 2_000.
        assertEquals(ConnectionEdge.Recovered(1_000L), tracker.observe("DEVICE_EVENTS|1", true, "", 2_000L))
    }
}
