package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.msgpack.core.MessagePack

class AnnounceCodecTest {

    private fun deliveryPayload(peerId: ByteArray): ByteArray = buildList {
        add(0x92.toByte()); add(0xc6.toByte()) // array(2), bin32
        add((peerId.size ushr 24).toByte()); add((peerId.size ushr 16).toByte())
        add((peerId.size ushr 8).toByte()); add(peerId.size.toByte())
        addAll(peerId.toList()); add(0xc0.toByte()) // nil stamp cost
    }.toByteArray()

    private fun bindingPayload(peerId: String, pubHex: String, sigHex: String): ByteArray =
        MessagePack.newDefaultBufferPacker().use { packer ->
            packer.packArrayHeader(3)
            packer.packString(peerId)
            packer.packString(pubHex)
            packer.packString(sigHex)
            packer.toByteArray()
        }

    @Test
    fun `valid delivery announce returns the peer id`() {
        assertEquals(
            "12D3KooWTest",
            AnnounceCodec.peerIdFromDelivery(deliveryPayload("12D3KooWTest".toByteArray()))
        )
    }

    @Test
    fun `oversized declared length is rejected without allocating`() {
        val p = byteArrayOf(0x92.toByte(), 0xc6.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xf0.toByte(), 0x41)
        val started = System.nanoTime()
        assertNull(AnnounceCodec.peerIdFromDelivery(p))
        assertTrue("must reject before allocating", (System.nanoTime() - started) / 1_000_000 < 50)
    }

    @Test
    fun `peer id over the cap is rejected`() {
        assertNull(AnnounceCodec.peerIdFromDelivery(deliveryPayload(ByteArray(129) { 'a'.code.toByte() })))
    }

    @Test
    fun `truncated payload is rejected`() {
        assertNull(AnnounceCodec.peerIdFromDelivery(byteArrayOf(0x92.toByte(), 0xc6.toByte(), 0, 0, 0, 10, 0x41)))
    }

    @Test
    fun `identity fields parse`() {
        val fields = AnnounceCodec.identityFields(bindingPayload("peer", "ab", "cd"))
        assertEquals(AnnounceCodec.IdentityFields("peer", "ab", "cd"), fields)
    }

    @Test
    fun `oversized identity field is rejected`() {
        assertNull(AnnounceCodec.identityFields(bindingPayload("peer", "ab", "c".repeat(257))))
    }
}
