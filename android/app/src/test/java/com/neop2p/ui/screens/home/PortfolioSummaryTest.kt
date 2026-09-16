package com.neop2p.ui.screens.home

import com.neop2p.data.local.entity.ChatMessageEntity
import com.neop2p.data.local.entity.EscrowEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioSummaryTest {

    private val me = "12D3KooWMe"
    private val peer = "12D3KooWPeer"

    private fun escrow(
        id: String,
        offerId: String,
        status: String,
        seller: String = me,
        deposit: Long = 100_000L
    ) = EscrowEntity(
        escrow_id = id,
        offer_id = offerId,
        deposit_amount_sats = deposit,
        trade_amount_sats = 99_500L,
        fee_amount_sats = 500L,
        fee_address = "tb1qfeewallet",
        buyer_peer_id = peer,
        seller_peer_id = seller,
        status = status
    )

    private fun msg(id: String, offerId: String, sender: String = peer, isRead: Boolean = false) =
        ChatMessageEntity(
            message_id = id,
            offer_id = offerId,
            sender_peer_id = sender,
            ciphertext = ByteArray(0),
            is_read = isRead,
            sent_at = 1_000L
        )

    @Test
    fun `open trades excludes terminal escrows`() {
        val header = portfolioSummary(
            escrows = listOf(
                escrow("e1", "offer_1", "FUNDED"),
                escrow("e2", "offer_2", "RELEASED"),
                escrow("e3", "offer_3", "CANCELLED"),
                escrow("e4", "offer_4", "REFUNDED")
            ),
            messages = emptyList(),
            myPeerId = me
        )
        assertEquals(1, header.openTrades)
    }

    @Test
    fun `locked sats counts only my own open escrows`() {
        val header = portfolioSummary(
            escrows = listOf(
                escrow("e1", "offer_1", "FUNDED", seller = me, deposit = 100_000L),
                escrow("e2", "offer_2", "FUNDED", seller = peer, deposit = 250_000L),
                escrow("e3", "offer_3", "RELEASED", seller = me, deposit = 900_000L)
            ),
            messages = emptyList(),
            myPeerId = me
        )
        assertEquals(100_000L, header.lockedSats)
    }

    @Test
    fun `unread counts only messages of offers that have an escrow`() {
        val header = portfolioSummary(
            escrows = listOf(escrow("e1", "offer_1", "FUNDED")),
            messages = listOf(
                msg("m1", "offer_1"),                  // escrow-backed, unread -> counts
                msg("m2", "offer_2"),                  // no escrow            -> no
                msg("m3", "offer_1", sender = me),     // own row              -> no
                msg("m4", "offer_1", isRead = true)    // already read         -> no
            ),
            myPeerId = me
        )
        assertEquals(1, header.unreadTotal)
    }

    @Test
    fun `a released trade's unread messages still count`() {
        // Deliberate: the message is genuinely unread wherever its thread
        // lives. With the reactive wiring (Task 3) it clears the instant the
        // user opens that thread from Trades, so it cannot linger.
        val header = portfolioSummary(
            escrows = listOf(escrow("e1", "offer_1", "RELEASED")),
            messages = listOf(msg("m1", "offer_1"), msg("m2", "offer_1"), msg("m3", "offer_1")),
            myPeerId = me
        )
        assertEquals(0, header.openTrades)
        assertEquals(3, header.unreadTotal)
    }
}
