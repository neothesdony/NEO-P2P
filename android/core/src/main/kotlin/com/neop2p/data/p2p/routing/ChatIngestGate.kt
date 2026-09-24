package com.neop2p.data.p2p.routing

/**
 * 2026-09-24: inbound chat is E2EE and sender-bound, but the sender is not
 * necessarily a party to the offer the payload claims. Without this gate any
 * peer with a verified RNS binding can open a session and overwrite
 * `trade_offers.payment_details` for an arbitrary offerId — redirecting the
 * buyer's fiat to the attacker. Only the offer creator (seller) or the matched
 * buyer may write into a thread. Fails closed on blank/null.
 */
object ChatIngestGate {
    fun mayIngest(senderPeerId: String, creatorPeerId: String?, matchedPeerId: String?): Boolean {
        if (senderPeerId.isBlank()) return false
        return senderPeerId == creatorPeerId || senderPeerId == matchedPeerId
    }
}
