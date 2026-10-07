package io.github.mvolkert.entryrecorder.domain.device

import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The monitor service logs one line per event, and the event is a data class wrapping a [DeviceEntity] —
 * so interpolating the event used to drop the HTTP and the SIP password straight into logcat. These tests
 * pin the contract of the replacement: [logIdentity] says which event arrived from which device, and says
 * nothing that identifies a credential. The entity's own diagnostic form is covered too, because that is
 * what any future log site would print.
 */
class IntercomEventLogIdentityTest {

    private val httpPassword = "httptestpw"
    private val sipPassword = "siptestpw"

    private val device = DeviceEntity(
        id = 7L,
        name = "Front Door",
        ipAddress = "192.168.1.50",
        username = "admin",
        password = httpPassword,
        sipPassword = sipPassword
    )

    private val events: List<IntercomEvent> = listOf(
        IntercomEvent.MotionStarted(device),
        IntercomEvent.MotionEnded(device),
        IntercomEvent.NoiseStarted(device),
        IntercomEvent.NoiseEnded(device),
        IntercomEvent.MotionOnDeviceStarted(device),
        IntercomEvent.MotionOnDeviceEnded(device),
        IntercomEvent.DoorbellRung(device, callerNumber = "+4912345"),
        IntercomEvent.ConnectionState(device, ConnectionQuality.ONLINE, "Connected"),
        IntercomEvent.Error(device, IllegalStateException("endpoint dead"))
    )

    @Test
    fun `identity names the event class and the device`() {
        for (event in events) {
            val identity = event.logIdentity()
            assertTrue("$identity misses its class", identity.contains(event.javaClass.simpleName))
            assertTrue("$identity misses the device id", identity.contains("7"))
            assertTrue("$identity misses the device name", identity.contains("Front Door"))
        }
    }

    @Test
    fun `identity never carries a credential`() {
        for (event in events) {
            val identity = event.logIdentity()
            assertFalse("$identity leaks the HTTP password", identity.contains(httpPassword))
            assertFalse("$identity leaks the SIP password", identity.contains(sipPassword))
            assertFalse("$identity looks like an entity dump", identity.contains("password="))
        }
    }

    @Test
    fun `device diagnostic form masks both passwords`() {
        val description = device.toString()
        assertFalse(description.contains(httpPassword))
        assertFalse(description.contains(sipPassword))
        assertTrue(description.contains("password=<redacted>"))
        assertTrue(description.contains("sipPassword=<redacted>"))
    }

    @Test
    fun `device diagnostic form keeps a null sip password null`() {
        val description = device.copy(sipPassword = null).toString()
        assertTrue(description.contains("sipPassword=null"))
    }
}
