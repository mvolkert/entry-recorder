package io.github.mvolkert.entryrecorder.data.local.crypto

/**
 * A credential that is encrypted at rest. Holds the plaintext the app works with; the Room
 * [CredentialConverters] turn it into ciphertext on write and back on read, so only the storage
 * boundary knows about encryption. Typed rather than a plain [String] so the converter applies to
 * exactly the credential columns and not every `String` in the schema.
 */
@JvmInline
value class EncryptedString(val value: String)
