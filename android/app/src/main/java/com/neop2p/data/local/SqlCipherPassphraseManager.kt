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

    private const val PREFS = "neop2p_db_key"
    private const val SALT_KEY = "db_salt_v1"

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
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Sampled BEFORE generation: an alias that appears during this call is a
        // brand-new install, not an existing one.
        val isNewInstall = !keyStore.containsAlias(KEYSTORE_ALIAS)
        if (isNewInstall) {
            generateAesWrappingKey()
        }

        val secretKey = keyStore.getKey(KEYSTORE_ALIAS, null) as javax.crypto.SecretKey

        // Use the AES key to encrypt the fixed seed, then SHA-256 the ciphertext.
        // This is deterministic: same AES key + same seed = same ciphertext = same passphrase.
        // GCM normally uses a random IV, but here we use a fixed IV because we are not
        // encrypting for confidentiality — we are deriving a stable passphrase.
        val fixedIv = ByteArray(12) { 0x00 } // 12 bytes, all zeros — acceptable for passphrase derivation
        val spec = javax.crypto.spec.GCMParameterSpec(128, fixedIv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val ciphertext = cipher.doFinal(FIXED_SEED.toByteArray(Charsets.UTF_8))

        // 2026-09-24: new installs derive from ciphertext + a random per-install
        // salt, so the passphrase is never a bare function of the KeyStore key.
        // Existing installs have no salt and keep the legacy derivation — no
        // rekey, no risk. (A future migration can PRAGMA rekey + persist a salt.)
        val salt = prefs.getString(SALT_KEY, null)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        val passphrase = when {
            salt != null ->
                MessageDigest.getInstance("SHA-256").digest(ciphertext + salt)
            isNewInstall -> {
                val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                prefs.edit()
                    .putString(
                        SALT_KEY,
                        android.util.Base64.encodeToString(fresh, android.util.Base64.NO_WRAP)
                    )
                    .apply()
                MessageDigest.getInstance("SHA-256").digest(ciphertext + fresh)
            }
            else -> MessageDigest.getInstance("SHA-256").digest(ciphertext)
        }

        cachedPassphrase = passphrase
        passphrase
    }

    /**
     * C10/B2 (2026-09-23): delete the KeyStore wrapping key and drop the
     * cached passphrase. Called on identity reset — the existing DB becomes
     * undecryptable, so the caller must also delete the DB files.
     */
    fun deleteKey() {
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.deleteEntry(KEYSTORE_ALIAS)
        }
        cachedPassphrase?.fill(0)
        cachedPassphrase = null
    }

    /**
     * Generate a dedicated AES-256 key in KeyStore for passphrase derivation.
     * This key is separate from the identity Ed25519 key and never leaves KeyStore.
     *
     * StrongBox is preferred when available, but it is optional hardware. On
     * devices/emulators without StrongBox the [android.security.keystore.StrongBoxUnavailableException]
     * is thrown at [KeyGenerator.generateKey] time, so we fall back to a TEE-backed key.
     *
     * [android.security.keystore.KeyGenParameterSpec.Builder.setRandomizedEncryptionRequired]
     * is set to false because we pass a caller-provided fixed IV for deterministic output.
     */
    private fun generateAesWrappingKey() {
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)

        // Try StrongBox first (hardware-backed). If unavailable, fall back to TEE.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                keyGen.init(buildSpec(strongBox = true))
                keyGen.generateKey()
                Log.d(TAG, "Generated StrongBox-backed AES-256 KeyStore wrapping key")
                return
            } catch (_: Exception) {
                // StrongBox unavailable (e.g. emulator) — fall through to TEE-backed key.
            }
        }

        keyGen.init(buildSpec(strongBox = false))
        keyGen.generateKey()

        Log.d(TAG, "Generated AES-256 KeyStore wrapping key for SQLCipher passphrase")
    }

    /**
     * A fresh builder per call — [KeyGenParameterSpec.Builder] is mutable, so a
     * builder that had `setIsStrongBoxBacked(true)` called on it must NOT be
     * reused for the TEE fallback.
     *
     * `setUnlockedDeviceRequired(true)` (API 28+): the key is unusable until the
     * device has been unlocked after boot, so a powered-off / pre-first-unlock
     * forensic image cannot derive the DB passphrase. Newly generated keys only;
     * existing installs keep their current key (no rekey — see [getPassphrase]).
     */
    private fun buildSpec(strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(false)
        if (strongBox) {
            builder.setIsStrongBoxBacked(true)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }
        return builder.build()
    }
}