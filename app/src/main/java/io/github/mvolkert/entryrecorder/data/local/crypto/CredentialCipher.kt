package io.github.mvolkert.entryrecorder.data.local.crypto

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies the AES key the [CredentialCipher] encrypts with. Production reads a non-exportable key
 * from the Android Keystore; tests hand back an ordinary software key so the transform is exercised
 * on the JVM without a device.
 */
fun interface SymmetricKeyProvider {
    fun key(): SecretKey
}

/**
 * Encrypts a single credential for storage at rest with AES/GCM. The output is self-describing — a
 * [PREFIX]-tagged Base64 of IV || ciphertext — so the storage migration can tell an already encrypted
 * value from legacy plaintext and never double-encrypts, and a decrypt that meets an un-prefixed value
 * returns it untouched rather than throwing. Empty strings pass through unchanged so "no credential
 * chosen yet" stays an empty column instead of ciphertext of nothing.
 */
class CredentialCipher(
    private val keyProvider: SymmetricKeyProvider,
) {
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return plaintext
        // The AndroidKeyStore Cipher forbids a caller-supplied IV on encryption and generates its own, so
        // the IV is read back off the initialised cipher rather than passed in. A software key (tests) takes
        // the same path: the default provider fills a fresh random IV per init, which `cipher.iv` returns.
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyProvider.key())
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty() || !stored.startsWith(PREFIX)) return stored
        val decoded = Base64.getDecoder().decode(stored.substring(PREFIX.length))
        val iv = decoded.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = decoded.copyOfRange(IV_LENGTH_BYTES, decoded.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keyProvider.key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /** True when [stored] is one of this cipher's own outputs, so a migration can skip it. */
    fun looksEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    companion object {
        const val PREFIX = "enc:v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_BITS = 128
    }
}
