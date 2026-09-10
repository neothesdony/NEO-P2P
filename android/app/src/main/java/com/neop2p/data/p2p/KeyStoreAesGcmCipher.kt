package com.neop2p.data.p2p

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM key held in the AndroidKeyStore, used to encrypt the BIP-39
 * identity seed at rest.
 *
 * P0-4: when the device has a lock-screen credential, the key is bound to
 * user authentication (validity window 300s) so decrypting the identity
 * requires a recent device unlock (PIN/pattern/biometric). Devices without
 * any credential keep a non-gated key — the app must never brick itself on
 * devices where KeyStore auth is impossible.
 *
 * StrongBox is preferred when available, with a TEE fallback (StrongBox is
 * optional hardware). The key material is never exported; callers must use the
 * key only inside the KeyStore (encrypt/decrypt operations).
 *
 * Locked state is surfaced to callers via the thrown exceptions:
 * - [android.security.keystore.UserNotAuthenticatedException]: device not
 *   unlocked within the validity window — prompt for unlock and retry.
 * - [android.security.keystore.KeyPermanentlyInvalidatedException]: the key
 *   was invalidated (e.g. lock-screen changed) — identity must be restored
 *   from the BIP-39 mnemonic.
 */
class KeyStoreAesGcmCipher(context: Context) : AesGcmCipher {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Whether this device has a lock-screen credential we can bind to. */
    private val authAvailable: Boolean = isDeviceCredentialConfigured(context)

    private val secretKey: SecretKey = if (keyStore.containsAlias(ALIAS)) {
        keyStore.getKey(ALIAS, null) as SecretKey
    } else {
        generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")

        // Try StrongBox first (hardware-backed). If unavailable, fall back to TEE.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                generator.init(spec(strongBox = true))
                return generator.generateKey()
            } catch (_: Exception) {
                // StrongBox unavailable (e.g. emulator) — fall through to TEE-backed key.
            }
        }

        generator.init(spec(strongBox = false))
        return generator.generateKey()
    }

    private fun spec(strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(false)
        if (strongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        if (authAvailable) {
            // 5-minute window: any successful device unlock (PIN/biometric)
            // authorizes the key. Background sync shortly after unlock works.
            builder
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(300)
        }
        return builder.build()
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
        private const val ALIAS = "neop2p_identity_seed"

        private fun isDeviceCredentialConfigured(context: Context): Boolean {
            return try {
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                km.isDeviceSecure
            } catch (_: Exception) {
                false
            }
        }
    }
}
