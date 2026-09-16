package com.neop2p.data.local

import com.neop2p.data.local.entity.ChatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home banner's unread semantics. Regression cover for the 2026-09-16
 * "3 unread" report: a released trade's thread kept a stale count because
 * (a) the count only recomputed on escrow writes and (b) our own outgoing
 * file placeholder was persisted with the read flag clear.
 */
class ChatUnreadPolicyTest {

    private val me = "12D3KooWMe"
    private val peer = "12D3KooWPeer"

    private fun row(
        id: String,
        offerId: String = "offer_1",
        sender: String = peer,
        isRead: Boolean = false,
        file: ByteArray? = null
    ) = ChatMessageEntity(
        message_id = id,
        offer_id = offerId,
        sender_peer_id = sender,
        ciphertext = ByteArray(0),
        is_read = isRead,
        sent_at = 1_000L,
        file_attachment = file
    )

    @Test
    fun `unread inbound counts`() {
        assertTrue(ChatUnreadPolicy.countsAsUnread(row("a"), me))
    }

    @Test
    fun `read inbound does not count`() {
        assertFalse(ChatUnreadPolicy.countsAsUnread(row("b", isRead = true), me))
    }

    @Test
    fun `own row persisted unread does not count`() {
        // ChatRouter.sendFile used to persist the sender's own placeholder with
        // sender_peer_id = peer and is_read = false, so every file the user
        // sent bumped their own unread badge.
        assertFalse(ChatUnreadPolicy.countsAsUnread(row("c", sender = me, file = ByteArray(4)), me))
    }

    @Test
    fun `unread total sums only unread inbound rows`() {
        val rows = listOf(
            row("d", offerId = "offer_1"),                       // unread inbound  -> counts
            row("e", offerId = "offer_1", isRead = true),        // read inbound    -> no
            row("f", offerId = "offer_1", sender = me),          // own row         -> no
            row("g", offerId = "offer_2")                        // unread inbound  -> counts
        )
        assertEquals(2, ChatUnreadPolicy.unreadTotal(rows, me))
    }

    @Test
    fun `blank identity does not hide an unread message`() {
        // Identity is locked behind device auth: we cannot prove the row is
        // ours, so it stays visible rather than being silently dropped.
        assertEquals(1, ChatUnreadPolicy.unreadTotal(listOf(row("h")), ""))
    }

    @Test
    fun `unread for offer scopes to one thread`() {
        val rows = listOf(
            row("i", offerId = "offer_1"),
            row("j", offerId = "offer_2"),
            row("k", offerId = "offer_2", isRead = true)
        )
        assertEquals(1, ChatUnreadPolicy.unreadForOffer(rows, "offer_1", me))
        assertEquals(1, ChatUnreadPolicy.unreadForOffer(rows, "offer_2", me))
        assertEquals(0, ChatUnreadPolicy.unreadForOffer(rows, "offer_3", me))
    }
}
