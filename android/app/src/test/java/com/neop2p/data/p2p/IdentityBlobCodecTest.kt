package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentityBlobCodecTest {

    private val sample = IdentityBlob(
        seedPhrase = listOf("abandon", "abandon", "about"),
        peerId = "12D3KooWabc123",
        nostrPubkeyHex = "0f".repeat(32),
        nostrPrivateKeyHex = "ff".repeat(32),
        nickname = "Trader One",
        lnNodeId = "030000000000000000000000000000000000000000000000000000000000000000"
    )

    @Test
    fun `round_trips all fields losslessly`() {
        val decoded = IdentityBlobCodec.decode(IdentityBlobCodec.encode(sample))
        assertEquals(sample.seedPhrase, decoded.seedPhrase)
        assertEquals(sample.peerId, decoded.peerId)
        assertEquals(sample.nostrPubkeyHex, decoded.nostrPubkeyHex)
        assertEquals(sample.nostrPrivateKeyHex, decoded.nostrPrivateKeyHex)
        assertEquals(sample.nickname, decoded.nickname)
        assertEquals(sample.lnNodeId, decoded.lnNodeId)
    }

    @Test
    fun `empty strings and unicode nickname survive`() {
        val blob = sample.copy(nickname = "Résumé 🧑", lnNodeId = "")
        val decoded = IdentityBlobCodec.decode(IdentityBlobCodec.encode(blob))
        assertEquals(blob, decoded)
    }

    @Test
    fun `single word seed phrase works`() {
        val blob = sample.copy(seedPhrase = listOf("zoo"))
        val decoded = IdentityBlobCodec.decode(IdentityBlobCodec.encode(blob))
        assertEquals(blob, decoded)
    }

    @Test
    fun `decode rejects truncated input`() {
        val bytes = IdentityBlobCodec.encode(sample)
        assertThrows(IllegalArgumentException::class.java) {
            IdentityBlobCodec.decode(bytes.copyOfRange(0, bytes.size - 5))
        }
    }

    @Test
    fun `decode rejects empty input`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdentityBlobCodec.decode(ByteArray(0))
        }
    }
}
