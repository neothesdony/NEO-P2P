package com.neop2p.ui.screens.trade

import com.neop2p.domain.model.Escrow
import com.neop2p.domain.model.EscrowRole
import com.neop2p.domain.model.OfferType
import com.neop2p.domain.model.TradeOffer
import org.junit.Assert.assertEquals
import org.junit.Test

class TradeRoomDataTest {

    private fun offer(creator: String, matched: String? = null, fiat: Long = 1_000_000L) = TradeOffer(
        offerId = "offer_1",
        creatorPeerId = creator,
        type = OfferType.SELL,
        fiatAmount = fiat,
        cryptoAmountSats = 100_000L,
        pricePerUnit = 10_000_000.0,
        fiatMethods = listOf("bca"),
        matchedPeerId = matched
    )

    private fun escrow(buyer: String, seller: String) = Escrow(
        escrowId = "escrow_1",
        offerId = "offer_1",
        depositAmountSats = 100_500L,
        tradeAmountSats = 100_000L,
        feeAmountSats = 500L,
        buyerPeerId = buyer,
        sellerPeerId = seller
    )

    @Test
    fun `buyer role when my id is the escrow buyer`() {
        val data = resolveTradeRoom(offer("seller"), escrow("buyer", "seller"), "buyer")
        assertEquals(EscrowRole.BUYER, data.role)
    }

    @Test
    fun `seller role when my id is the escrow seller`() {
        val data = resolveTradeRoom(offer("seller"), escrow("buyer", "seller"), "seller")
        assertEquals(EscrowRole.SELLER, data.role)
    }

    @Test
    fun `unknown role when no escrow yet`() {
        val data = resolveTradeRoom(offer("seller"), null, "buyer")
        assertEquals(EscrowRole.UNKNOWN, data.role)
    }

    @Test
    fun `creator chats with the matched peer`() {
        val data = resolveTradeRoom(offer("seller", matched = "buyer"), null, "seller")
        assertEquals("buyer", data.peerId)
    }

    @Test
    fun `taker chats with the creator`() {
        val data = resolveTradeRoom(offer("seller", matched = "buyer"), null, "buyer")
        assertEquals("seller", data.peerId)
    }

    @Test
    fun `payment details and fiat amount pass through`() {
        val o = offer("seller", matched = "buyer", fiat = 2_500_000L)
        val data = resolveTradeRoom(o, null, "buyer")
        assertEquals(2_500_000L, data.fiatAmount)
        assertEquals(o.paymentDetails, data.paymentDetails)
    }
}
