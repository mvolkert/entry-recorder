package io.github.mvolkert.entryrecorder.data.local.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [SymmetricKeyProvider] backed by the hardware Android Keystore: a 256-bit AES key generated once,
 * marked non-exportable and unlocked with the device (no user authentication requirement, so it is
 * available to a background service). The raw bytes never leave the keystore, which is the whole point
 * of the at-rest guarantee. Keystore-only, so it is not exercised by the JVM unit suite.
 */
class AndroidKeystoreCredentialKeyProvider(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : SymmetricKeyProvider {

    override fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return createKey()
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "entry_recorder_credential_key"
    }
}
