package com.neop2p.ui.screens.chathistory

import com.neop2p.data.local.entity.ChatMessageEntity
import com.neop2p.data.local.entity.TradeOfferEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Home "Chats" list composition. The list must reach EVERY conversation
 * the device persisted a message for — including finished trades, whose chat
 * is otherwise unreachable from the Trades tab (terminal escrows open the
 * escrow detail, not the chat).
 */
class ChatHistoryTest {

    private val me = "12D3KooWMe"
    private val peerA = "12D3KooWPeerA"
    private val peerB = "12D3KooWPeerB"

    private fun offer(
        offerId: String,
        creator: String,
        matched: String?,
        status: String = "COMPLETED",
        createdAt: Long = 1_000L
    ) = TradeOfferEntity(
        offer_id = offerId,
        creator_peer_id = creator,
        type = "SELL",
        fiat_amount = 1_000_000L,
        crypto_amount_sats = 100_000L,
        price_per_unit = 1.0,
        status = status,
        created_at = createdAt,
        matched_peer_id = matched
    )

    private fun msg(id: String, offerId: String, sentAt: Long, sender: String = peerA, isRead: Boolean = false) =
        ChatMessageEntity(
            message_id = id,
            offer_id = offerId,
            sender_peer_id = sender,
            ciphertext = ByteArray(0),
            is_read = isRead,
            sent_at = sentAt
        )

    @Test
    fun `threads are newest-first`() {
        val threads = chatThreads(
            messages = listOf(
                msg("m1", "offer_old", sentAt = 1_000L),
                msg("m2", "offer_new", sentAt = 9_000L)
            ),
            offers = listOf(
                offer("offer_old", creator = me, matched = peerA),
                offer("offer_new", creator = peerB, matched = me)
            ),
            myPeerId = me
        )
        assertEquals(listOf("offer_new", "offer_old"), threads.map { it.offerId })
    }

    @Test
    fun `counterparty is the matched peer when I am the creator`() {
        val threads = chatThreads(
            messages = listOf(msg("m1", "offer_1", sentAt = 1_000L)),
            offers = listOf(offer("offer_1", creator = me, matched = peerA)),
            myPeerId = me
        )
        assertEquals(peerA, threads.single().peerId)
    }

    @Test
    fun `counterparty is the creator when I am the taker`() {
        val threads = chatThreads(
            messages = listOf(msg("m1", "offer_1", sentAt = 1_000L, sender = peerB)),
            offers = listOf(offer("offer_1", creator = peerB, matched = me)),
            myPeerId = me
        )
        assertEquals(peerB, threads.single().peerId)
    }

    @Test
    fun `thread with no offer row is skipped`() {
        val threads = chatThreads(
            messages = listOf(msg("m1", "offer_gone", sentAt = 1_000L)),
            offers = emptyList(),
            myPeerId = me
        )
        assertEquals(emptyList<ChatThread>(), threads)
    }

    @Test
    fun `thread with a blank counterparty is skipped`() {
        val threads = chatThreads(
            messages = listOf(msg("m1", "offer_1", sentAt = 1_000L)),
            offers = listOf(offer("offer_1", creator = me, matched = null)),
            myPeerId = me
        )
        assertEquals(emptyList<ChatThread>(), threads)
    }

    @Test
    fun `unread count is per thread and keeps the offer status`() {
        val threads = chatThreads(
            messages = listOf(
                msg("m1", "offer_1", sentAt = 1_000L),                    // unread inbound
                msg("m2", "offer_1", sentAt = 2_000L, sender = me),       // own row
                msg("m3", "offer_2", sentAt = 3_000L, isRead = true),     // read
                msg("m4", "offer_2", sentAt = 4_000L)                     // unread inbound
            ),
            offers = listOf(
                offer("offer_1", creator = me, matched = peerA, status = "FUNDED"),
                offer("offer_2", creator = peerB, matched = me, status = "COMPLETED")
            ),
            myPeerId = me
        )
        val byId = threads.associateBy { it.offerId }
        assertEquals(1, byId.getValue("offer_1").unread)
        assertEquals("FUNDED", byId.getValue("offer_1").status)
        assertEquals(1, byId.getValue("offer_2").unread)
        assertEquals("COMPLETED", byId.getValue("offer_2").status)
    }
}
