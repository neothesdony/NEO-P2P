package com.neop2p.data.p2p

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
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
 * 2026-09-24: `isDeviceSecure` used to be sampled only when the key was first
 * generated, so an identity created before a lock screen existed kept a
 * permanently un-gated seed key. [ensureAuthBound] performs a one-time re-wrap
 * the next time a secure device loads its identity (see [SeedKeyAuthPolicy]).
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

    private val prefs: SharedPreferences =
        context.getSharedPreferences("neop2p_identity", Context.MODE_PRIVATE)

    /** True when the CURRENT key was generated with a user-auth requirement. */
    val authBound: Boolean
        get() = prefs.getBoolean(MARKER_AUTH_BOUND, false)

    // Mutable: [ensureAuthBound] replaces the in-memory handle with the fresh
    // key so the caller can immediately decrypt the re-wrapped blob.
    private var secretKey: SecretKey = if (keyStore.containsAlias(ALIAS)) {
        keyStore.getKey(ALIAS, null) as SecretKey
    } else {
        generateKey().also { prefs.edit().putBoolean(MARKER_AUTH_BOUND, authAvailable).apply() }
    }

    /**
     * One-time re-wrap: if the device is now secure but the existing key was
     * generated un-gated, decrypt the current blob with the old key, delete
     * the alias, generate an auth-gated key, and return the re-encrypted
     * `[iv || ciphertext || tag]` blob (or null when no retrofit is needed or
     * possible). The caller persists the returned blob.
     *
     * Never bricks: if auth-gated generation fails after the alias is deleted,
     * a usable un-gated key is regenerated and the marker is left false so the
     * retrofit retries on a later load.
     */
    fun ensureAuthBound(currentBlob: ByteArray?): ByteArray? {
        if (!SeedKeyAuthPolicy.needsRetrofit(authAvailable, authBound)) return null
        if (currentBlob == null) return null
        val plaintext = try {
            decrypt(currentBlob.copyOfRange(0, 12), currentBlob.copyOfRange(12, currentBlob.size))
        } catch (_: Exception) {
            return null
        }
        val (fresh, bound) = rotateToAuthKey()
        secretKey = fresh
        prefs.edit().putBoolean(MARKER_AUTH_BOUND, bound).apply()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return try {
            iv + encrypt(plaintext, iv)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Replace the alias with an auth-gated key. Returns the fresh key plus
     * whether it is actually auth-bound; on generation failure a usable
     * un-gated key is regenerated instead (never brick the alias).
     */
    private fun rotateToAuthKey(): Pair<SecretKey, Boolean> = try {
        keyStore.deleteEntry(ALIAS)
        generateKey(requireAuth = true) to true
    } catch (_: Exception) {
        generateKey(requireAuth = false) to false
    }

    private fun generateKey(requireAuth: Boolean = authAvailable): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")

        // Try StrongBox first (hardware-backed). If unavailable, fall back to TEE.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                generator.init(spec(strongBox = true, requireAuth = requireAuth))
                return generator.generateKey()
            } catch (_: Exception) {
                // StrongBox unavailable (e.g. emulator) — fall through to TEE-backed key.
            }
        }

        generator.init(spec(strongBox = false, requireAuth = requireAuth))
        return generator.generateKey()
    }

    private fun spec(strongBox: Boolean, requireAuth: Boolean = authAvailable): KeyGenParameterSpec {
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
        if (requireAuth) {
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
        private const val MARKER_AUTH_BOUND = "seed_key_auth_bound"

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
