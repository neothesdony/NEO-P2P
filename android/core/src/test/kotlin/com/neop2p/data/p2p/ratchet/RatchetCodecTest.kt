package com.neop2p.data.p2p.ratchet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RatchetCodecTest {

    private fun state() = RatchetState(
        rootKey = ByteArray(32) { 1 },
        dhSelfPriv = ByteArray(32) { 2 },
        dhSelfPub = ByteArray(32) { 3 },
        dhRemotePub = ByteArray(32) { 4 },
        sendChainKey = ByteArray(32) { 5 },
        sendCount = 7,
        recvChainKey = ByteArray(32) { 6 },
        recvCount = 9,
        prevChainLength = 4,
        recvEpoch = 2,
        skipped = mapOf(RatchetState.skippedKey(1, 3) to ByteArray(32) { 8 }),
        remoteInitialPub = ByteArray(32) { 9 }
    )

    @Test
    fun `encode-decode round-trips every field`() {
        val decoded = RatchetCodec.decode(RatchetCodec.encode(state()))
        assertEquals(7L, decoded.sendCount)
        assertEquals(9L, decoded.recvCount)
        assertEquals(4L, decoded.prevChainLength)
        assertEquals(2L, decoded.recvEpoch)
        assertArrayEquals(state().rootKey, decoded.rootKey)
        assertArrayEquals(state().dhSelfPriv, decoded.dhSelfPriv)
        assertArrayEquals(state().dhRemotePub, decoded.dhRemotePub)
        assertArrayEquals(state().sendChainKey, decoded.sendChainKey)
        assertArrayEquals(state().recvChainKey, decoded.recvChainKey)
        assertArrayEquals(state().remoteInitialPub, decoded.remoteInitialPub)
        assertEquals(1, decoded.skipped.size)
        assertArrayEquals(ByteArray(32) { 8 }, decoded.skipped[RatchetState.skippedKey(1, 3)])
    }

    @Test
    fun `skippedKey namespaces by epoch`() {
        assertEquals(RatchetState.skippedKey(0, 5), RatchetState.skippedKey(0, 5))
        org.junit.Assert.assertNotEquals(RatchetState.skippedKey(1, 5), RatchetState.skippedKey(2, 5))
    }

    @Test
    fun `decode rejects a truncated blob`() {
        assertThrows(IllegalArgumentException::class.java) {
            RatchetCodec.decode(ByteArray(3))
        }
    }

    @Test
    fun `decode rejects a wrong magic`() {
        val bytes = RatchetCodec.encode(state())
        bytes[0] = 0
        assertThrows(IllegalArgumentException::class.java) { RatchetCodec.decode(bytes) }
    }
}
