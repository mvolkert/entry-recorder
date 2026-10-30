package io.github.mvolkert.entryrecorder.push

import com.google.gson.Gson
import io.github.mvolkert.entryrecorder.testing.FakeMotionAlertPushStore
import io.github.mvolkert.entryrecorder.testing.RecordingRemoteMotionAlertPresenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-free half of `add-motion-notification-to-other-phone`'s "receive a relayed motion alert on this
 * phone" requirement: what a delivered push body has to look like before this phone turns it into a
 * notification. The bytes are exactly what the Python server POSTs
 * (see `server/entry_recorder_server/alert_relay.py`), so the contract is pinned from the reader's side without
 * a distributor, a second phone or a network.
 */
class RemoteMotionAlertHandlerTest {

    private val store = FakeMotionAlertPushStore().apply { enabled = true }
    private val presenter = RecordingRemoteMotionAlertPresenter()
    private val handler = RemoteMotionAlertHandler(store, presenter, Gson())

    /** The body the server builds for a front-door trigger, written the way `build_alert_body` writes it. */
    private val validBody = """{"kind":"entry_recorder.motion","deviceName":"Front Door","monitorName":"Kitchen phone","timestampMs":1700000000000}"""

    private fun deliver(content: String, decrypted: Boolean = true) =
        handler.onAlert(content.toByteArray(Charsets.UTF_8), decrypted)

    @Test
    fun `a contract payload raises exactly one alert with its fields intact`() {
        deliver(validBody)

        val alert = presenter.shown.single()
        assertEquals("Front Door", alert.deviceName)
        assertEquals("Kitchen phone", alert.monitorName)
        assertEquals(1_700_000_000_000L, alert.timestampMs)
    }

    @Test
    fun `an undecrypted payload is dropped rather than guessed at`() {
        deliver(validBody, decrypted = false)

        assertTrue(presenter.shown.isEmpty())
    }

    @Test
    fun `a payload for another kind is not a motion alert`() {
        deliver("""{"kind":"some.other.app","deviceName":"Front Door","timestampMs":1700000000000}""")

        assertTrue(presenter.shown.isEmpty())
    }

    @Test
    fun `a missing kind is not a motion alert either`() {
        deliver("""{"deviceName":"Front Door","timestampMs":1700000000000}""")

        assertTrue(presenter.shown.isEmpty())
    }

    @Test
    fun `malformed JSON raises nothing`() {
        deliver("this is not json")

        assertTrue(presenter.shown.isEmpty())
    }

    @Test
    fun `an empty body raises nothing`() {
        deliver("")

        assertTrue(presenter.shown.isEmpty())
    }

    @Test
    fun `a payload that names no camera raises nothing`() {
        deliver("""{"kind":"entry_recorder.motion","deviceName":"  ","timestampMs":1700000000000}""")

        assertTrue(presenter.shown.isEmpty())
    }

    @Test
    fun `a payload without a usable timestamp is still an alert, stamped as it arrived`() {
        val alert = parseRemoteMotionAlert(
            content = """{"kind":"entry_recorder.motion","deviceName":"Gate"}""".toByteArray(Charsets.UTF_8),
            gson = Gson(),
            nowMs = 1_700_000_009_000L,
        )

        assertEquals("Gate", alert?.deviceName)
        assertEquals("", alert?.monitorName)
        assertEquals(1_700_000_009_000L, alert?.timestampMs)
    }

    @Test
    fun `a phone the user opted out of ignores its registration entirely`() {
        store.enabled = false

        deliver(validBody)

        assertTrue(presenter.shown.isEmpty())
        assertFalse(store.isReceiveEnabled())
    }

    @Test
    fun `the registration state the settings screen reads follows the distributor`() {
        handler.onEndpoint("https://ntfy.sh/secret-token", needsEncryption = false)
        assertEquals("https://ntfy.sh/secret-token", store.endpoint())
        assertFalse(store.endpointNeedsEncryption())

        // A Web Push distributor hands back keys: the address cannot be filled by a plain relay body.
        handler.onEndpoint("https://push.example.org/p/abc", needsEncryption = true)
        assertTrue(store.endpointNeedsEncryption())

        handler.onUnregistered()
        assertNull(store.endpoint())

        handler.onEndpoint("https://ntfy.sh/another", needsEncryption = false)
        handler.onRegistrationFailed("NETWORK")
        assertNull(store.endpoint())
    }
}
