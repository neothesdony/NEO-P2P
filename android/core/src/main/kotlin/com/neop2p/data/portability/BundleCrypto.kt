package com.neop2p.data.portability

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BundleCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Passphrase envelope for an exported identity bundle (Phase 3, C5, 2026-09-23).
 * Layout: magic("NP2B") | version(1) | salt(16) | iv(12) | AES-256-GCM(ct||tag).
 * PBKDF2-HMAC-SHA256 with 210k iterations. Device-bound KeyStore keys cannot
 * travel, so the key is derived from a user passphrase instead.
 */
object BundleCrypto {
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'P'.code.toByte(), '2'.code.toByte(), 'B'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val HEADER_LEN = 4 + 1 + SALT_LEN + IV_LEN

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)
        return MAGIC + byteArrayOf(VERSION) + salt + iv + ct
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        if (blob.size < HEADER_LEN + 1 || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw BundleCryptoException("Not a NEO-P2P identity bundle")
        }
        if (blob[4] != VERSION) throw BundleCryptoException("Unsupported bundle envelope version ${blob[4]}")
        val salt = blob.copyOfRange(5, 5 + SALT_LEN)
        val iv = blob.copyOfRange(5 + SALT_LEN, 5 + SALT_LEN + IV_LEN)
        val ct = blob.copyOfRange(HEADER_LEN, blob.size)
        val key = deriveKey(passphrase, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ct)
        } catch (e: Exception) {
            throw BundleCryptoException("Wrong passphrase or corrupt bundle", e)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
