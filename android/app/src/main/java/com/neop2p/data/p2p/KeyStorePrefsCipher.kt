package com.neop2p.data.p2p

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM key held in the AndroidKeyStore for the SharedPreferences
 * encryption tier (C2, 2026-09-11).
 *
 * Deliberately NOT auth-gated (unlike [KeyStoreAesGcmCipher]'s
 * `neop2p_identity_seed`): the background sweep reads pending disputes while
 * the device is locked, and a UserNotAuthenticatedException there would drop
 * retries. Trade-off: a device thief with root can decrypt prefs — same
 * protection level as the identity seed minus the auth gate. Documented in
 * SECURITY_POSTURE.md.
 */
class KeyStorePrefsCipher(context: Context) : AesGcmCipher {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private val secretKey: SecretKey = if (keyStore.containsAlias(ALIAS)) {
        keyStore.getKey(ALIAS, null) as SecretKey
    } else {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(false)
                        .build()
                )
            }
            .generateKey()
    }

    override fun encrypt(plaintext: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return c.doFinal(plaintext)
    }

    override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return c.doFinal(ciphertextWithTag)
    }

    companion object {
        private const val ALIAS = "neop2p_prefs_key"
    }
}
