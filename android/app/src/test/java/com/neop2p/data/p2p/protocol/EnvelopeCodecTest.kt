package com.neop2p.data.p2p.protocol

import com.neop2p.data.p2p.P2PTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnvelopeCodecTest {

    private fun roundTrip(msg: AppMessage, from: String, to: String): AppMessage? {
        val env = EnvelopeCodec.encode(msg)
        return EnvelopeCodec.decode(env.copy(fromPeerId = from, toPeerId = to))
    }

    @Test
    fun `encode_chat_round_trips`() {
        val msg = AppMessage.Chat(to = "peerB", offerId = "offer1", ciphertext = byteArrayOf(1, 2, 3, 0, 9))
        val decoded = roundTrip(msg, "peerA", "peerB") as AppMessage.Chat
        assertEquals("peerB", decoded.to)
        assertEquals("peerA", decoded.from)
        assertEquals("offer1", decoded.offerId)
        assertArrayEquals(msg.ciphertext, decoded.ciphertext)
    }

    @Test
    fun `encode_pre_key_bundle_round_trips_with_binary_payload`() {
        val msg = AppMessage.PreKeyBundle(to = "peerA", bundle = ByteArray(128) { it.toByte() })
        val decoded = roundTrip(msg, "peerB", "peerA")
        assertEquals("peerA", (decoded as AppMessage.PreKeyBundle).to)
        assertEquals("peerB", decoded.from)
        assertArrayEquals(msg.bundle, decoded.bundle)
    }

    @Test
    fun `encode_offer_round_trips`() {
        val msg = AppMessage.Offer(to = "peerB", offerJson = """{"k":1}""")
        val decoded = roundTrip(msg, "peerA", "peerB") as AppMessage.Offer
        assertEquals("peerB", decoded.to)
        assertEquals("peerA", decoded.from)
        assertEquals(msg.offerJson, decoded.offerJson)
    }

    @Test
    fun `decode_rejects_empty_data`() {
        val env = P2PTransport.TransportMessage(type = "chat", fromPeerId = "a", toPeerId = "b")
        assertNull(EnvelopeCodec.decode(env))
    }

    @Test
    fun `decode_rejects_unknown_type`() {
        val env = P2PTransport.TransportMessage(type = "garbage", fromPeerId = "a", toPeerId = "b", data = byteArrayOf(1))
        assertNull(EnvelopeCodec.decode(env))
    }
}
