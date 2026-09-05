package com.neop2p.domain.model

import com.neop2p.NeoP2PConfig

import kotlinx.serialization.Serializable

@Serializable
data class Peer(
    val peerId: String,
    val nickname: String,
    val nostrPubkey: String,
    val lnNodeId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val reputationScore: Float = 0f,
    val totalTrades: Int = 0,
    val lastSeen: Long = System.currentTimeMillis(),
    val relayHints: List<String> = emptyList(),
    val multiaddrs: List<String> = emptyList()
)

@Serializable
data class TradeOffer(
    val offerId: String,
    val creatorPeerId: String,
    val type: OfferType,
    val asset: CryptoAsset = CryptoAsset.BTC,
    val fiatAmount: Long,
    val cryptoAmountSats: Long,
    val pricePerUnit: Double,
    val feePercent: Double = NeoP2PConfig.FEE_PERCENT,
    // Dust floor enforced: max(0.5%, MIN_FEE_SATS) so the payout's fee
    // output is always relayable (a sub-dust fee is dropped by the payout
    // builder and silently lost to the miner). Integer-only: 0.5% = sats*5/1000
    // (exact for every Long sats value — no float rounding on money).
    val feeSats: Long = maxOf((cryptoAmountSats * 5) / 1000, NeoP2PConfig.MIN_FEE_SATS),
    val fiatMethods: List<String>,
    // BTC receive address — set when the creator is the BUYER (the BTC recipient).
    // Kept off the public Nostr event; exchanged securely later (see P0-1).
    val btcReceiveAddress: String = "",
    val status: OfferStatus = OfferStatus.OPEN,
    val createdAt: Long = System.currentTimeMillis(),
    val nostrEventId: String? = null,
    // Peer that accepted/locked the offer (from the LXMF offer_status status event).
    // Used to route chat correctly: the creator of a locked offer chats with
    // the acceptor, not with themselves.
    val matchedPeerId: String? = null,
    // P2P payment details (bank number + holder name) keyed by fiat method id.
    // Exchanged ONLY via E2EE chat after a taker commits — never published
    // to the public Nostr relay (see P0-1).
    val paymentDetails: Map<String, PaymentDetails> = emptyMap(),
    // Offer lifetime (epoch millis). NULL = never expires (legacy offers).
    // Stale offers stay visible-but-blocked: the accept gate refuses claims
    // past this deadline and the home feed greys them out.
    val expiresAt: Long? = null,
    // Epoch millis when the offer became MATCHED. NULL = not locked (or an
    // unlocked/legacy row). Local-only lifecycle metadata — never published.
    val lockedAt: Long? = null
) {
    /** New model: the seller pays the full 0.5% fee; the buyer pays nothing and
     * receives the full crypto amount. The seller's fee is deducted from the
     * payout to the fee wallet. */
    val buyerFeeSats: Long get() = 0
    val sellerFeeSats: Long get() = feeSats
    val totalDepositSats: Long get() = cryptoAmountSats + feeSats
}

/** Payment details required for a fiat method (e.g. bank account). */
@kotlinx.serialization.Serializable
data class PaymentDetails(
    val accountNumber: String = "",
    val accountHolder: String = "",
    // QRIS: the seller's static QRIS string (NMID-based) the buyer scans
    // with their e-wallet app. Empty for bank/e-wallet rails.
    val qrisString: String = ""
)

enum class OfferType { BUY, SELL }
enum class OfferStatus {
    OPEN, PAUSED, MATCHED, ESCROWED, COMPLETED, DISPUTED, CANCELLED
}

enum class CryptoAsset(val ticker: String) {
    BTC("BTC")
}
