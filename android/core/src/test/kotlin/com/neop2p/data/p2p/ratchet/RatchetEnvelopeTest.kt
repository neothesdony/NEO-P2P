package com.neop2p.data.p2p.ratchet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RatchetEnvelopeTest {

    private fun header() = RatchetHeader(
        dhPub = ByteArray(32) { 7 },
        msgNum = 3,
        prevChainLength = 1
    )

    @Test
    fun `header-only round-trips`() {
        val decoded = RatchetEnvelope.decodeHeader(RatchetEnvelope.encodeHeader(header()))
        assertArrayEquals(header().dhPub, decoded.dhPub)
        assertEquals(3L, decoded.msgNum)
        assertEquals(1L, decoded.prevChainLength)
    }

    @Test
    fun `header plus ciphertext round-trips`() {
        val ct = ByteArray(40) { 9 }
        val (h, body) = RatchetEnvelope.decode(RatchetEnvelope.encode(header(), ct))
        assertArrayEquals(header().dhPub, h.dhPub)
        assertEquals(3L, h.msgNum)
        assertArrayEquals(ct, body)
    }

    @Test
    fun `decode rejects a bad magic`() {
        val bytes = RatchetEnvelope.encode(header(), ByteArray(40))
        bytes[0] = 0
        assertThrows(IllegalArgumentException::class.java) { RatchetEnvelope.decode(bytes) }
    }

    @Test
    fun `AAD changes when any bound field changes`() {
        val base = RatchetAad.build("s", "p", "o", header())
        val other = RatchetAad.build("s", "p", "o2", header())
        org.junit.Assert.assertFalse(base.contentEquals(other))
    }
}
