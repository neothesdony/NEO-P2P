package com.neop2p.data.local

import com.neop2p.data.p2p.AesGcmCipher
import com.neop2p.data.p2p.SeedCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** JVM fake — AndroidKeyStore is unavailable in plain JUnit (same pattern as SeedCipherTest). */
private class FakeAesGcmCipher : AesGcmCipher {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
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

class EncryptedPrefsStoreTest {

    private fun store() = EncryptedPrefsStore.forTest(FakeAesGcmCipher())

    @Test
    fun `round-trips plaintext`() {
        val s = store()
        val blob = s.encrypt("{\"bca\":{\"accountNumber\":\"1234567890\"}}")
        assertEquals("{\"bca\":{\"accountNumber\":\"1234567890\"}}", s.decrypt(blob))
    }

    @Test
    fun `ciphertext is not plaintext`() {
        val s = store()
        val blob = s.encrypt("secret-bank-number")
        assertNull("plaintext must not appear in the blob", blob.takeIf { it.contains("secret-bank-number") })
    }

    @Test
    fun `legacy plaintext is transparently migrated`() {
        val s = store()
        // A legacy value is not base64-GCM shaped (no iv prefix) — decrypt
        // returns it verbatim so callers can re-encrypt on next write.
        assertEquals("legacy-json", s.decrypt("legacy-json"))
    }

    @Test
    fun `tampered blob fails to null`() {
        val s = store()
        val blob = s.encrypt("value")
        val tampered = blob.dropLast(1) + if (blob.last() == 'A') 'B' else 'A'
        assertNull(s.decrypt(tampered))
    }
}
