package com.neop2p.data.portability

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleCryptoTest {
    private val plaintext = "the quick brown fox".toByteArray()

    @Test
    fun roundTripWithCorrectPassphrase() {
        val blob = BundleCrypto.encrypt(plaintext, "correct horse".toCharArray())
        assertArrayEquals(plaintext, BundleCrypto.decrypt(blob, "correct horse".toCharArray()))
    }

    @Test
    fun wrongPassphraseFailsClosed() {
        val blob = BundleCrypto.encrypt(plaintext, "right".toCharArray())
        assertThrows(BundleCryptoException::class.java) {
            BundleCrypto.decrypt(blob, "wrong".toCharArray())
        }
    }

    @Test
    fun ciphertextIsNotPlaintext() {
        val blob = BundleCrypto.encrypt(plaintext, "pw".toCharArray())
        assertFalse(blob.toString(Charsets.ISO_8859_1).contains("quick"))
    }

    @Test
    fun tamperedBlobFailsClosed() {
        val blob = BundleCrypto.encrypt(plaintext, "pw".toCharArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        assertThrows(BundleCryptoException::class.java) {
            BundleCrypto.decrypt(blob, "pw".toCharArray())
        }
    }

    @Test
    fun hasMagicHeader() {
        val blob = BundleCrypto.encrypt(plaintext, "pw".toCharArray())
        assertTrue(blob.size > 33)
        assertArrayEquals("NP2B".toByteArray(), blob.copyOfRange(0, 4))
    }
}
