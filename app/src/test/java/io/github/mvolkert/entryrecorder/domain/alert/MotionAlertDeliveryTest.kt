package io.github.mvolkert.entryrecorder.domain.alert

import io.github.mvolkert.entryrecorder.util.ConnectionSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The rule `add-connection-loss-logging` sets and the motion-alert change has to keep: only a transport failure
 * says anything about the link to the Python server. Now that the server answers 502 when the recipient's push
 * distributor refused, a monitor-side line that read "python-server lost" would declare the server down after it
 * had just been reached — so the relay's failure wording is pinned here, together with the bearer-address rule
 * that keeps the recipient's token out of the log file.
 */
class MotionAlertDeliveryTest {

    private val refusedRecipient = "https://ntfy.sh/c2VjcmV0LXRva2Vu"

    @Test
    fun `a refused relay is reported as a failed delivery, never as a lost link`() {
        val line = MotionAlertDelivery.failure(
            deviceName = "Front Door",
            recipient = refusedRecipient,
            cause = Exception("HTTP 502: push endpoint rejected the alert"),
        )

        assertTrue(line.contains("not delivered"))
        assertFalse("a 502 came from a reachable server", line.contains(ConnectionSource.PythonServer.label))
        assertFalse(line.contains("lost"))
        assertTrue(line.contains("Front Door"))
    }

    @Test
    fun `the bearer token in the recipient address never reaches the line`() {
        val line = MotionAlertDelivery.failure(
            deviceName = "Front Door",
            recipient = refusedRecipient,
            cause = IOException("connect failed"),
        )

        assertFalse("the path is the token", line.contains("c2VjcmV0LXRva2Vu"))
        assertTrue("the origin is what identifies the target", line.contains("https://ntfy.sh"))
    }

    @Test
    fun `a failure with no message still says what class refused`() {
        val line = MotionAlertDelivery.failure("Gate", "https://ntfy.sh/abc", IllegalStateException())

        assertTrue(line.contains("IllegalStateException"))
        assertTrue(line.contains("Gate"))
    }

    @Test
    fun `an unparsable address is named by a fixed marker instead of being echoed`() {
        // A half-typed recipient is exactly the case where a token can end up glued to garbage, so the raw
        // value must not be printed at all.
        val line = MotionAlertDelivery.failure("Gate", "ntfy.sh/not-an-url", IOException("timeout"))

        assertTrue(line.contains("<unparsable-address>"))
        assertFalse(line.contains("not-an-url"))
    }
}
