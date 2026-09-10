package com.neop2p.ui.screens.trade

import com.neop2p.domain.model.Escrow
import com.neop2p.domain.model.EscrowRole
import com.neop2p.domain.model.PaymentDetails
import com.neop2p.domain.model.TradeOffer

/**
 * Everything the trade hub needs to render, derived from the offer + escrow
 * rows and the current identity. Pure so it is unit-testable without Android.
 */
data class TradeRoomData(
    val offer: TradeOffer,
    val escrow: Escrow?,
    val role: EscrowRole,
    val peerId: String,
    val paymentDetails: Map<String, PaymentDetails>,
    val fiatAmount: Long
)

/**
 * Resolve the hub state for [myPeerId]:
 *  - role comes from the escrow's buyer/seller peer ids (UNKNOWN before the
 *    escrow row exists — e.g. the buyer of a SELL offer pre-sync);
 *  - chat target: the offer creator talks to the matched peer, the taker
 *    talks to the creator.
 *
 * NOTE (single-key model): role resolution is BUYER-FIRST, matching
 * EscrowService.roleFor (EscrowService.kt:1831-1838). In the single-key demo
 * one device holds BOTH role peerIds, so the seller's own device resolves
 * BUYER and shows the buyer view — identical to the escrow screen today.
 * This is existing, known behavior; the hub must NOT "fix" it or the two
 * screens would disagree. QA should expect the seller side to look like the
 * buyer side on a single-key device.
 */
fun resolveTradeRoom(offer: TradeOffer, escrow: Escrow?, myPeerId: String): TradeRoomData {
    val role = when (myPeerId) {
        escrow?.buyerPeerId -> EscrowRole.BUYER
        escrow?.sellerPeerId -> EscrowRole.SELLER
        else -> EscrowRole.UNKNOWN
    }
    val peerId = when {
        offer.creatorPeerId == myPeerId -> offer.matchedPeerId ?: ""
        else -> offer.creatorPeerId
    }
    return TradeRoomData(
        offer = offer,
        escrow = escrow,
        role = role,
        peerId = peerId,
        paymentDetails = offer.paymentDetails,
        fiatAmount = offer.fiatAmount
    )
}
