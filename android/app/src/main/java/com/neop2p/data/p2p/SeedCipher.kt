package com.neop2p.data.p2p

import java.security.SecureRandom

/**
 * Encrypts/decrypts an identity blob using AES-GCM. The output layout is
 * [iv (12 bytes)][ciphertext || tag], so a single blob round-trips via [decrypt].
 * The raw cipher is delegated to an [AesGcmCipher] so it can be unit-tested on the
 * JVM (AndroidKeyStore is unavailable in plain JUnit).
 */
class SeedCipher(private val gcm: AesGcmCipher) {

    /** Underlying cipher, exposed for KeyStore auth-gate checks (P0-4). */
    val cipher: AesGcmCipher get() = gcm

    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val ct = gcm.encrypt(plaintext, iv)
        return iv + ct
    }

    fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < 12) throw IllegalArgumentException("Ciphertext blob too short")
        val iv = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        return gcm.decrypt(iv, ct)
    }
}
