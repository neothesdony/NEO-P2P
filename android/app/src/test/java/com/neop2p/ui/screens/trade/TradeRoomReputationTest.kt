package com.neop2p.ui.screens.trade

import com.neop2p.data.reputation.ReputationSystem.PeerReputation
import com.neop2p.domain.model.EscrowRole
import com.neop2p.domain.model.OfferType
import com.neop2p.domain.model.TradeOffer
import org.junit.Assert.assertEquals
import org.junit.Test

class TradeRoomReputationTest {

    private fun offer() = TradeOffer(
        offerId = "o",
        creatorPeerId = "me",
        type = OfferType.SELL,
        fiatAmount = 1L,
        cryptoAmountSats = 1L,
        pricePerUnit = 1.0,
        fiatMethods = emptyList()
    )

    private fun base() = TradeRoomData(
        offer = offer(),
        escrow = null,
        role = EscrowRole.SELLER,
        peerId = "buyer",
        paymentDetails = emptyMap(),
        fiatAmount = 0L,
        isCreator = true
    )

    @Test
    fun defaultReputationIsNull() {
        assertEquals(null, base().counterpartyReputation)
    }

    @Test
    fun reputationIsCopiedWithoutTouchingResolve() {
        val withRep = base().copy(
            counterpartyReputation = PeerReputation(peerId = "buyer", totalTrades = 3)
        )
        assertEquals("buyer", withRep.counterpartyReputation?.peerId)
        assertEquals("buyer", withRep.peerId)
    }
}
