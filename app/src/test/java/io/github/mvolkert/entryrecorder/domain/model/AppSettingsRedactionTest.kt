package io.github.mvolkert.entryrecorder.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redaction guarantee the file-capture change relies on: secrets are collapsed at the source, so a
 * model interpolated into any log — logcat or the shared file — never carries a credential in clear. [AppSettings]
 * is a plain data class whose default `toString()` would print the decrypted `serverApiKey`, so its override
 * is pinned here; [Device]'s override is asserted alongside it as the same guarantee for the HTTP/SIP passwords.
 */
class AppSettingsRedactionTest {

    @Test
    fun `a logged settings row never carries the server api key in clear`() {
        val secret = "hunter2-server-key"
        val logged = AppSettings(serverApiKey = secret, serverBaseUrl = "http://192.168.1.50:8000").toString()

        assertTrue("the credential field should collapse to the redaction marker", logged.contains("<redacted>"))
        assertFalse("the raw api key must not be logged", logged.contains(secret))
        // Redaction is targeted, not blanket — non-secret diagnostics stay readable.
        assertTrue("other fields remain visible", logged.contains("http://192.168.1.50:8000"))
    }

    @Test
    fun `a logged device never carries its passwords in clear`() {
        val httpPassword = "verso-http-pw"
        val sipPassword = "verso-sip-pw"
        val logged = Device(
            name = "Front gate",
            ipAddress = "192.168.1.20",
            password = httpPassword,
            sipPassword = sipPassword,
        ).toString()

        assertTrue("the device prints the redaction marker", logged.contains("<redacted>"))
        assertFalse(logged.contains(httpPassword))
        assertFalse(logged.contains(sipPassword))
        assertTrue("the address stays diagnostic", logged.contains("192.168.1.20"))
    }

    @Test
    fun `a logged settings row never carries the motion alert address in clear`() {
        // The recipient became a UnifiedPush endpoint, so its path is the bearer token: whoever reads the log
        // could push to that phone. The flag next to it stays printed, since "alerts are on" is a diagnostic.
        val token = "secret-subscriber-path"
        val logged = AppSettings(
            motionAlertEnabled = true,
            motionAlertRecipient = "https://ntfy.sh/$token",
        ).toString()

        assertFalse("the endpoint token must not be logged", logged.contains(token))
        assertFalse(logged.contains("ntfy.sh"))
        assertTrue(logged.contains("<redacted>"))
        assertTrue("the flag itself stays readable", logged.contains("motionAlertEnabled=true"))
    }

    @Test
    fun `the file-logging flag is off by default so capture never starts silently`() {
        // A fresh settings row must default to no file capture; the toggle is opt-in.
        assertFalse(AppSettings().loggingToFileEnabled)
    }
}
