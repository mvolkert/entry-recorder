package io.github.mvolkert.entryrecorder.data.local.crypto

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter

/**
 * Room boundary for [EncryptedString]: the value goes to the column as ciphertext and comes back as
 * plaintext. Provided (not a static `@TypeConverters` block) because it needs the injected
 * [CredentialCipher], whose key lives in the Android Keystore. Registered on the database builder with
 * `addTypeConverter`.
 */
@ProvidedTypeConverter
class CredentialConverters(private val cipher: CredentialCipher) {

    @TypeConverter
    fun fromEncrypted(value: EncryptedString): String = cipher.encrypt(value.value)

    @TypeConverter
    fun toEncrypted(stored: String): EncryptedString = EncryptedString(cipher.decrypt(stored))
}
