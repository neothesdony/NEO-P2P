package com.neop2p.ui.screens.history

import com.neop2p.data.local.entity.TradeOfferEntity
import com.neop2p.domain.model.OfferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An accepted (MATCHED) SELL offer has no escrow until the seller creates it,
 * so the Trades tab used to show nothing for it. These tests pin the pure
 * merge that surfaces the accepted offer while the escrow is pending.
 */
class HistoryAwaitingEscrowTest {

    private val me = "peer_me"
    private val other = "peer_other"

    private fun matchedOffer(
        id: String,
        creator: String,
        matched: String?,
        status: String = OfferStatus.MATCHED.name,
        fiat: Long = 100_000L,
        created: Long = 1_000L
    ) = TradeOfferEntity(
        offer_id = id,
        creator_peer_id = creator,
        type = "SELL",
        fiat_amount = fiat,
        crypto_amount_sats = 10_000L,
        price_per_unit = 1.0,
        status = status,
        created_at = created,
        matched_peer_id = matched
    )

    @Test
    fun buyerSeesAcceptedOfferAsWaiting() {
        val rows = awaitingEscrowRows(
            listOf(matchedOffer("o1", creator = other, matched = me)),
            escrowedOfferIds = emptySet(),
            myPeerId = me
        )
        assertEquals(1, rows.size)
        assertEquals("o1", rows[0].offerId)
        assertEquals(100_000L, rows[0].fiatAmount)
        assertFalse(rows[0].needsMyAction)
        assertFalse(rows[0].iAmSeller)
    }

    @Test
    fun sellerSeesMatchedOfferAsAction() {
        val rows = awaitingEscrowRows(
            listOf(matchedOffer("o1", creator = me, matched = other)),
            escrowedOfferIds = emptySet(),
            myPeerId = me
        )
        assertEquals(1, rows.size)
        assertTrue(rows[0].needsMyAction)
        assertTrue(rows[0].iAmSeller)
    }

    @Test
    fun escrowedOfferIsExcluded() {
        val rows = awaitingEscrowRows(
            listOf(matchedOffer("o1", creator = other, matched = me)),
            escrowedOfferIds = setOf("o1"),
            myPeerId = me
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun offerNotInvolvingMeIsExcluded() {
        val rows = awaitingEscrowRows(
            listOf(matchedOffer("o1", creator = "peer_a", matched = "peer_b")),
            escrowedOfferIds = emptySet(),
            myPeerId = me
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun nonMatchedStatusIsExcluded() {
        val rows = awaitingEscrowRows(
            listOf(matchedOffer("o1", creator = other, matched = me, status = OfferStatus.ESCROWED.name)),
            escrowedOfferIds = emptySet(),
            myPeerId = me
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun blankPeerIdYieldsNothing() {
        val rows = awaitingEscrowRows(
            listOf(matchedOffer("o1", creator = other, matched = me)),
            escrowedOfferIds = emptySet(),
            myPeerId = ""
        )
        assertTrue(rows.isEmpty())
    }
}
