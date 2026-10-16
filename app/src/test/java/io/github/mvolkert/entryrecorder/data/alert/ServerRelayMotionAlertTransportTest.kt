package io.github.mvolkert.entryrecorder.data.alert

import io.github.mvolkert.entryrecorder.domain.alert.MotionAlert
import io.github.mvolkert.entryrecorder.testing.FakeServerClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 2.2 of `add-motion-notification-to-other-phone`: the server-relay transport is the primary rung of the
 * alert ladder and must forward every [MotionAlert] field to [FakeServerClient.relayMotionAlert] unchanged,
 * mapping the [Result] straight back. Pure delegation over the seam, so no network, no clock, no device.
 */
class ServerRelayMotionAlertTransportTest {

    private val alert = MotionAlert(
        serverBaseUrl = "http://192.168.1.50:8000",
        apiKey = "secret",
        recipient = "pixel-andre",
        deviceName = "Front Door",
        timestampMs = 1_700_000_000_000L,
    )

    @Test
    fun `send forwards every alert field to the server relay`() = runTest {
        val server = FakeServerClient()
        val transport = ServerRelayMotionAlertTransport(server)

        val result = transport.send(alert)

        assertTrue(result.isSuccess)
        val call = server.relayMotionAlertCalls.single()
        assertEquals(alert.serverBaseUrl, call.serverUrl)
        assertEquals(alert.apiKey, call.apiKey)
        assertEquals(alert.recipient, call.recipient)
        assertEquals(alert.deviceName, call.deviceName)
        assertEquals(alert.timestampMs, call.timestampMs)
    }

    @Test
    fun `a relay failure surfaces as a failed Result rather than a throw`() = runTest {
        val server = FakeServerClient().apply {
            relayMotionAlertResult = Result.failure(Exception("HTTP 502"))
        }
        val transport = ServerRelayMotionAlertTransport(server)

        val result = transport.send(alert)

        assertTrue(result.isFailure)
        assertEquals("HTTP 502", result.exceptionOrNull()?.message)
    }
}
