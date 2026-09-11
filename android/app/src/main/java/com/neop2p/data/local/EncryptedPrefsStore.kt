package com.neop2p.data.local

import android.content.Context
import com.neop2p.data.p2p.AesGcmCipher
import com.neop2p.data.p2p.KeyStorePrefsCipher
import com.neop2p.data.p2p.SeedCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts SharedPreferences string values with AES-256-GCM (C2, 2026-09-11).
 *
 * Layout: base64( [iv 12 bytes][ciphertext || tag] ). Legacy plaintext
 * values are returned verbatim by [decrypt] so callers can re-encrypt on
 * the next write — a one-shot migration with no downtime.
 *
 * The KeyStore key is deliberately NOT auth-gated (see [KeyStorePrefsCipher]).
 *
 * Uses java.util.Base64 (NOT android.util.Base64): the store is exercised by
 * plain-JUnit tests, where android.util.Base64 returns null under
 * unitTests.isReturnDefaultValues and would NPE every test. java.util.Base64
 * is available on Android API 26+ (minSdk 26) and on the JDK 21 test JVM.
 */
@Singleton
class EncryptedPrefsStore private constructor(
    private val seedCipher: SeedCipher
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(SeedCipher(KeyStorePrefsCipher(context)))

    companion object {
        /** JVM-test factory (AndroidKeyStore is unavailable in plain JUnit). */
        fun forTest(cipher: AesGcmCipher): EncryptedPrefsStore =
            EncryptedPrefsStore(SeedCipher(cipher))
    }

    fun encrypt(plaintext: String): String =
        Base64.getEncoder().encodeToString(seedCipher.encrypt(plaintext.toByteArray(Charsets.UTF_8)))

    /**
     * Decrypt a stored blob. Returns the plaintext, or the input VERBATIM
     * when it is not a valid GCM blob (legacy plaintext — caller re-encrypts
     * on next write), or null when the blob is corrupt/tampered.
     */
    fun decrypt(blob: String): String? {
        if (blob.isBlank()) return blob
        val raw = try {
            Base64.getDecoder().decode(blob)
        } catch (_: IllegalArgumentException) {
            return blob // legacy plaintext
        }
        if (raw.size < 12) return blob // legacy plaintext
        return try {
            String(seedCipher.decrypt(raw), Charsets.UTF_8)
        } catch (_: Exception) {
            null // tampered / wrong key
        }
    }
}
