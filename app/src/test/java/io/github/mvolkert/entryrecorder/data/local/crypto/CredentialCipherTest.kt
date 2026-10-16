package io.github.mvolkert.entryrecorder.data.local.crypto

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential transform, exercised with a software AES key so it runs on the JVM without the
 * Android Keystore. Covers exactly the guarantees the storage migration relies on: a faithful
 * round-trip, empty passthrough, a self-describing marker so a re-run never double-encrypts, and
 * legacy plaintext decrypting to itself.
 */
class CredentialCipherTest {

    // One stable key per cipher, mirroring how the production provider returns the same cached
    // Keystore key on every call. A provider that re-generated would make decrypt see a different key.
    private fun newCipher(): CredentialCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return CredentialCipher(SymmetricKeyProvider { key })
    }

    @Test
    fun `round trips a credential`() {
        val cipher = newCipher()
        val stored = cipher.encrypt("hunter2")
        assertTrue(cipher.looksEncrypted(stored))
        assertNotEquals("hunter2", stored)
        assertEquals("hunter2", cipher.decrypt(stored))
    }

    @Test
    fun `leaves an empty credential empty`() {
        val cipher = newCipher()
        assertEquals("", cipher.encrypt(""))
        assertEquals("", cipher.decrypt(""))
    }

    @Test
    fun `decrypting legacy plaintext without the marker returns it unchanged`() {
        val cipher = newCipher()
        assertEquals("plaintext-secret", cipher.decrypt("plaintext-secret"))
        assertFalse(cipher.looksEncrypted("plaintext-secret"))
    }

    @Test
    fun `an already encrypted value is recognised so a migration can skip it`() {
        val cipher = newCipher()
        assertTrue(cipher.looksEncrypted(cipher.encrypt("secret")))
    }

    @Test
    fun `each encryption uses a fresh nonce yet decrypts to the same secret`() {
        val cipher = newCipher()
        val first = cipher.encrypt("secret")
        val second = cipher.encrypt("secret")
        assertNotEquals(first, second)
        assertEquals("secret", cipher.decrypt(first))
        assertEquals("secret", cipher.decrypt(second))
    }
}
