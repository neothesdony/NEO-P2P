package com.neop2p.data.p2p.routing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageFactoryTest {

    @Test
    fun `outgoing file placeholder is already read and keeps its bytes`() {
        // Regression: the sender's own placeholder was persisted with the read
        // flag clear and sender_peer_id = peer, so attaching a file bumped the
        // sender's own unread count forever. The bytes must still be persisted
        // so reloading history renders a file bubble, not "session unavailable".
        val bytes = byteArrayOf(1, 2, 3, 4)
        val row = ChatMessageFactory.outboundFile(
            messageId = "m1",
            offerId = "offer_1",
            peerId = "12D3KooWPeer",
            sentAt = 1_000L,
            fileAttachment = bytes
        )
        assertTrue(row.is_read)
        assertEquals(0, row.ciphertext.size)
        assertEquals("offer_1", row.offer_id)
        assertEquals(1_000L, row.sent_at)
        assertArrayEquals(bytes, row.file_attachment)
    }

    @Test
    fun `inbound chat row stays unread`() {
        val row = ChatMessageFactory.inbound(
            messageId = "m2",
            offerId = "offer_1",
            fromPeerId = "12D3KooWPeer",
            ciphertext = byteArrayOf(1, 2, 3),
            plaintext = byteArrayOf(4, 5, 6),
            sentAt = 2_000L
        )
        assertFalse(row.is_read)
        assertEquals("12D3KooWPeer", row.sender_peer_id)
        assertEquals(3, row.ciphertext.size)
        assertArrayEquals(byteArrayOf(4, 5, 6), row.plaintext)
    }

    @Test
    fun `file label matches the history placeholder format`() {
        assertEquals("[File: proof.png, 5 bytes]", ChatMessageFactory.fileLabel("proof.png", 5))
    }
}
