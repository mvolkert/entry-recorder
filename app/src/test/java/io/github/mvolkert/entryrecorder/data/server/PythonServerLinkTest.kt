package io.github.mvolkert.entryrecorder.data.server

import io.github.mvolkert.entryrecorder.util.ConnectionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The single python-server outage clock. The app talks to one server from several places — the control-plane
 * calls and the live-view relay — so the link must be keyed on the server *origin*, not on the endpoint URL,
 * or one unplugged server prints a loss line per path and never a recovery. Transition counting itself belongs
 * to [io.github.mvolkert.entryrecorder.util.ConnectionLogTest]; what is proven here is the keying, the
 * transport-only rule, and that the producers share one clock.
 */
class PythonServerLinkTest {

    private val apiCall = "http://192.168.1.5:8000/api/recordings/start"
    private val relayStream = "http://192.168.1.5:8000/api/live/3/mjpeg"

    @Test
    fun `a request url keys on the server origin`() {
        assertEquals("http://192.168.1.5:8000", PythonServerLink.keyFor(apiCall))
        assertEquals("http://192.168.1.5:8000", PythonServerLink.keyFor(relayStream))
        assertEquals("http://192.168.1.5:8000", PythonServerLink.keyFor("http://192.168.1.5:8000/"))
        // The port the scheme already implies is not repeated in the line.
        assertEquals("http://cam.local", PythonServerLink.keyFor("http://cam.local:80/status"))
        assertEquals("https://cam.local", PythonServerLink.keyFor("https://cam.local:443/api/status"))
        assertEquals("https://cam.local:8443", PythonServerLink.keyFor("https://cam.local:8443/api/status"))
    }

    @Test
    fun `an unparseable base url still keys stably`() {
        // Users type the endpoint without a scheme; the probe still has to compare against one key.
        assertEquals("192.168.1.5:8000", PythonServerLink.keyFor("192.168.1.5:8000/"))
    }

    @Test
    fun `the key never carries a query that could hold the api key`() {
        val secret = "SuperSecretApiKey"
        val key = PythonServerLink.keyFor("http://192.168.1.5:8000/api/live/3/mjpeg?api_key=$secret")
        assertFalse(key.contains(secret))
        assertEquals("http://192.168.1.5:8000", key)
    }

    @Test
    fun `the api call and the live relay share one outage clock`() {
        val link = PythonServerLink()

        assertEquals(ConnectionEdge.Lost("connect failed"), link.reportFailure(apiCall, IOException("connect failed")))
        // A second path onto the same dead server is the same outage, not a second loss line.
        assertEquals(ConnectionEdge.None, link.reportFailure(relayStream, IOException("connection refused")))
        assertEquals(ConnectionEdge.None, link.reportFailure(relayStream, "live relay closed by the server"))

        assertTrue(link.reportReachable(relayStream) is ConnectionEdge.Recovered)
        // The link is up, so the other producer's healthy read prints nothing.
        assertEquals(ConnectionEdge.None, link.reportReachable(apiCall))
    }

    @Test
    fun `an error answer from the server is not a lost link`() {
        val link = PythonServerLink()
        // A 500 came from a live server: only a transport failure may announce a loss.
        assertEquals(ConnectionEdge.None, link.reportFailure(apiCall, IllegalStateException("HTTP 500")))
        assertEquals(ConnectionEdge.Lost("timeout"), link.reportFailure(apiCall, IOException("timeout")))
    }

    @Test
    fun `a second server keeps its own clock`() {
        val link = PythonServerLink()
        val otherServer = "http://192.168.1.9:8000/api/status"
        assertEquals(ConnectionEdge.Lost("unreachable"), link.reportFailure(apiCall, IOException("unreachable")))
        // A different origin is a different link, so it announces its own loss rather than staying silent.
        assertEquals(ConnectionEdge.Lost("unreachable"), link.reportFailure(otherServer, IOException("unreachable")))
        assertEquals(ConnectionEdge.None, link.reportFailure(apiCall, IOException("still down")))
        // Recovering the first says nothing about the second, which stays down until its own read says so.
        assertTrue(link.reportReachable(apiCall) is ConnectionEdge.Recovered)
        assertEquals(ConnectionEdge.None, link.reportReachable(apiCall))
    }
}
