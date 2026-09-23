package com.neop2p.data.p2p.ratchet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChaChaAeadTest {

    private val key = ByteArray(32) { it.toByte() }
    private val aad = "aad|offer-1|peer-a".toByteArray()
    private val plaintext = "halo, ini pesan rahasia untuk dagang bitcoin".toByteArray()

    @Test
    fun `seal-open round-trips with matching AAD`() {
        val ct = ChaChaAead.seal(key, aad, plaintext)
        assertArrayEquals(plaintext, ChaChaAead.open(key, aad, ct))
    }

    @Test
    fun `open fails when the AAD is tampered`() {
        val ct = ChaChaAead.seal(key, aad, plaintext)
        assertThrows(Exception::class.java) {
            ChaChaAead.open(key, "aad|offer-2|peer-a".toByteArray(), ct)
        }
    }

    @Test
    fun `open fails when the ciphertext is tampered`() {
        val ct = ChaChaAead.seal(key, aad, plaintext)
        ct[ct.size - 1] = (ct[ct.size - 1] + 1).toByte()
        assertThrows(Exception::class.java) { ChaChaAead.open(key, aad, ct) }
    }

    @Test
    fun `a fresh nonce is used per seal`() {
        val a = ChaChaAead.seal(key, aad, plaintext)
        val b = ChaChaAead.seal(key, aad, plaintext)
        org.junit.Assert.assertFalse(a.contentEquals(b))
    }
}
