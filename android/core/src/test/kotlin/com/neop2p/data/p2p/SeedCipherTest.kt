package com.neop2p.data.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private class JvmAesGcmCipher(private val key: SecretKey) : AesGcmCipher {
    override fun encrypt(plaintext: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return c.doFinal(plaintext)
    }
    override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return c.doFinal(ciphertextWithTag)
    }
}

class SeedCipherTest {

    private fun newCipher(): SeedCipher {
        val kg = KeyGenerator.getInstance("AES").apply { init(256) }
        return SeedCipher(JvmAesGcmCipher(kg.generateKey()))
    }

    @Test
    fun `round trips arbitrary bytes`() {
        val cipher = newCipher()
        val plaintext = ByteArray(64) { it.toByte() }
        assertArrayEquals(plaintext, cipher.decrypt(cipher.encrypt(plaintext)))
    }

    @Test
    fun `output is iv prefixed to ciphertext`() {
        val cipher = newCipher()
        val plaintext = "secret".toByteArray(Charsets.UTF_8)
        val blob = cipher.encrypt(plaintext)
        // 12-byte random IV prefix, then ciphertext (which for AES-GCM includes the 16-byte tag)
        assert(blob.size >= 12 + 16)
        assertArrayEquals(plaintext, cipher.decrypt(blob))
    }

    @Test
    fun `encrypt uses a fresh random IV each time`() {
        val cipher = newCipher()
        val plaintext = byteArrayOf(1, 2, 3)
        val blob1 = cipher.encrypt(plaintext)
        val blob2 = cipher.encrypt(plaintext)
        // Two encryptions of the same plaintext must differ (fresh IV)
        assert(!blob1.contentEquals(blob2))
    }

    @Test
    fun `decrypt rejects truncated blob`() {
        val cipher = newCipher()
        val blob = cipher.encrypt(byteArrayOf(1, 2, 3))
        assertThrows(IllegalArgumentException::class.java) {
            cipher.decrypt(blob.copyOfRange(0, 5))
        }
    }

    @Test
    fun `decrypt rejects tampered ciphertext`() {
        val cipher = newCipher()
        val blob = cipher.encrypt("data".toByteArray(Charsets.UTF_8))
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertThrows(Exception::class.java) { cipher.decrypt(tampered) }
    }
}
