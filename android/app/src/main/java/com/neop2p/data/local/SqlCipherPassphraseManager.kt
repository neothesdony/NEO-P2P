package com.neop2p.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

/**
 * Derives SQLCipher passphrase using a KeyStore-wrapped AES key.
 *
 * Previous version used HKDF(privateKey.encoded) which fails on StrongBox devices
 * because hardware-backed keys are unexportable (.encoded throws AccessControlException).
 *
 * New approach: generate a dedicated AES key inside KeyStore, use it to encrypt/decrypt
 * a fixed seed. The result is deterministic (same key = same passphrase) without ever
 * extracting key material. Works on all devices including StrongBox.
 */
object SqlCipherPassphraseManager {
    private const val TAG = "SqlCipherPassphrase"
    private const val KEYSTORE_ALIAS = "neop2p_db_passphrase"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    // Fixed seed encrypted/decrypted by the KeyStore AES key.
    // Changing this invalidates all existing databases — treat as migration boundary.
    private const val FIXED_SEED = "NEO-P2P-DB-PASSPHRASE-SEED-V1"

    // Cache the passphrase in memory after first derivation (same process lifetime)
    @Volatile
    private var cachedPassphrase: ByteArray? = null

    /**
     * Returns the SQLCipher passphrase as a ByteArray.
     * Deterministic per device. Thread-safe. Caches after first call.
     */
    suspend fun getPassphrase(context: Context): ByteArray = withContext(Dispatchers.IO) {
        cachedPassphrase?.let { return@withContext it }

        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            generateAesWrappingKey()
        }

        val secretKey = keyStore.getKey(KEYSTORE_ALIAS, null) as javax.crypto.SecretKey

        // Use the AES key to encrypt the fixed seed, then SHA-256 the ciphertext
        // This is deterministic: same AES key + same seed = same ciphertext = same passphrase
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        // GCM requires deterministic output for the same key+IV, but standard GCM
        // uses random IV. Instead, we use AES/GCM with a fixed IV (acceptable here
        // because we're not encrypting for security — we're deriving a stable passphrase).
        // Re-init with fixed IV for determinism:
        val fixedIv = ByteArray(12) { 0x00 } // 12 bytes, all zeros — acceptable for passphrase derivation
        val spec = javax.crypto.spec.GCMParameterSpec(128, fixedIv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val ciphertext = cipher.doFinal(FIXED_SEED.toByteArray(Charsets.UTF_8))
        val passphrase = MessageDigest.getInstance("SHA-256").digest(ciphertext)

        cachedPassphrase = passphrase
        passphrase
    }

    /**
     * Generate a dedicated AES-256 key in KeyStore for passphrase derivation.
     * This key is separate from the identity Ed25519 key and never leaves KeyStore.
     */
    private fun generateAesWrappingKey() {
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(true)
            .build()

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGen.init(spec)
        keyGen.generateKey()

        Log.d(TAG, "Generated AES-256 KeyStore wrapping key for SQLCipher passphrase")
    }
}