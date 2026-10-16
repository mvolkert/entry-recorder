package io.github.mvolkert.entryrecorder.data.network

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Digest challenge parser the snapshot / event clients lean on for a preemptive Authorization
 * header, without any socket: [DigestAuthenticator.preemptiveHeader] must produce a well-formed Digest line
 * for a usable challenge, return null (so the caller keeps the Basic fast path) when the challenge lacks the
 * mandatory realm/nonce, and — the reason it is not a naive `split(",")` — read quoted parameter values that
 * themselves contain commas. The response hash is salted by a random cnonce, so the assertions check the
 * header's structure and parameters, never the digest value.
 */
class DigestAuthenticatorTest {

    private val authenticator = DigestAuthenticator(username = "admin", password = "2n")

    private val request = Request.Builder()
        .url("http://camera.local/api/camera/snapshot?width=1280")
        .get()
        .build()

    @Test
    fun `offersDigest distinguishes Digest from Basic`() {
        assertTrue(authenticator.offersDigest("Digest realm=\"x\", nonce=\"y\""))
        assertTrue(authenticator.offersDigest("digest realm=\"x\""))
        assertFalse(authenticator.offersDigest("Basic realm=\"x\""))
    }

    @Test
    fun `a usable challenge yields a well-formed Digest header`() {
        val header = authenticator.preemptiveHeader(
            "Digest realm=\"2N\", nonce=\"abc123\", qop=\"auth\", algorithm=MD5, opaque=\"op9\"",
            request,
        )
        assertNotNull(header)
        assertTrue(header!!.startsWith("Digest "))
        assertTrue(header.contains("username=\"admin\""))
        assertTrue(header.contains("realm=\"2N\""))
        assertTrue(header.contains("nonce=\"abc123\""))
        assertTrue(header.contains("uri=\"/api/camera/snapshot?width=1280\""))
        assertTrue(header.contains("response=\""))
        assertTrue(header.contains("qop=auth"))
        assertTrue(header.contains("nc=00000001"))
        assertTrue(header.contains("cnonce=\""))
        assertTrue(header.contains("opaque=\"op9\""))
        assertTrue(header.endsWith("algorithm=MD5"))
    }

    @Test
    fun `a challenge without qop omits nc and cnonce`() {
        val header = authenticator.preemptiveHeader("Digest realm=\"r\", nonce=\"n\"", request)
        assertNotNull(header)
        assertFalse(header!!.contains("qop="))
        assertFalse(header.contains("nc="))
        assertFalse(header.contains("cnonce="))
    }

    @Test
    fun `a challenge missing realm yields no header`() {
        assertNull(authenticator.preemptiveHeader("Digest nonce=\"abc\"", request))
    }

    @Test
    fun `a challenge missing nonce yields no header`() {
        assertNull(authenticator.preemptiveHeader("Digest realm=\"only-realm\"", request))
    }

    @Test
    fun `an empty challenge yields no header`() {
        assertNull(authenticator.preemptiveHeader("Digest", request))
    }

    @Test
    fun `quoted parameter values keep their embedded commas`() {
        // A naive comma split would shatter this realm into "Inner" and a bogus param and lose the nonce.
        val header = authenticator.preemptiveHeader(
            "Digest realm=\"2N, Verso 8190\", nonce=\"n1\", qop=\"auth\"",
            request,
        )
        assertNotNull(header)
        assertTrue(header!!.contains("realm=\"2N, Verso 8190\""))
        assertTrue(header.contains("nonce=\"n1\""))
    }

    @Test
    fun `a default algorithm is assumed when the challenge omits one`() {
        val header = authenticator.preemptiveHeader("Digest realm=\"r\", nonce=\"n\"", request)
        assertNotNull(header)
        assertTrue(header!!.endsWith("algorithm=MD5"))
    }

    @Test
    fun `uri carries the query exactly once`() {
        val header = authenticator.preemptiveHeader("Digest realm=\"r\", nonce=\"n\"", request)
        assertNotNull(header)
        assertEquals(1, Regex("uri=\"").findAll(header!!).count())
        assertTrue(header.contains("uri=\"/api/camera/snapshot?width=1280\""))
    }
}
