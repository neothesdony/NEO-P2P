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
    val feeSats: Long = (cryptoAmountSats * feePercent).toLong(),
    val fiatMethods: List<String>,
    val status: OfferStatus = OfferStatus.OPEN,
    val createdAt: Long = System.currentTimeMillis(),
    val nostrEventId: String? = null
) {
    /**
     * Fee is split 50/50 between buyer and seller (0.5% each, total 1%).
     * Buyer pays their half on top of the deposit; seller's half is deducted from payout.
     */
    val buyerFeeSats: Long get() = feeSats / 2
    val sellerFeeSats: Long get() = feeSats - buyerFeeSats // handles odd sats
    val totalDepositSats: Long get() = cryptoAmountSats + buyerFeeSats
}

enum class OfferType { BUY, SELL }
enum class OfferStatus {
    OPEN, MATCHED, ESCROWED, COMPLETED, DISPUTED, CANCELLED
}

enum class CryptoAsset(val ticker: String) {
    BTC("BTC")
}

@Serializable
data class Escrow(
    val escrowId: String,
    val offerId: String,
    val type: EscrowType = EscrowType.ON_CHAIN,
    val fundingTxId: String? = null,
    val payoutTxId: String? = null,
    val fundingAddress: String? = null,       // 2-of-3 P2SH multisig address
    val fundingAddressPath: String? = null,   // BIP-32 derivation path for the address
    val redeemScriptHex: String? = null,      // 2-of-3 redeem script (hex) — required to sign the payout
    val psbtUnsigned: ByteArray? = null,      // Serialized unsigned PSBT
    val psbtBuyerSigned: ByteArray? = null,   // PSBT after buyer signs
    val depositAmountSats: Long,
    val tradeAmountSats: Long,
    val feeAmountSats: Long,
    val feeAddress: String = NeoP2PConfig.FEE_WALLET_ADDRESS,
    val buyerPeerId: String,
    val sellerPeerId: String,
    val status: EscrowStatus = EscrowStatus.FUNDING,
    val buyerSignature: ByteArray? = null,
    val sellerSignature: ByteArray? = null,
    val arbitratorSignature: ByteArray? = null,
    val arbitratorDecision: String? = null,
    val arbitratorNotes: String? = null,
    val channelPoint: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val releasedAt: Long? = null
)

enum class EscrowType { ON_CHAIN }
enum class EscrowStatus {
    FUNDING, FUNDED, SIGNED, RELEASED, DISPUTED, RESOLVING, REFUNDED
}
enum class ResolutionDecision {
    /** Buyer paid, seller ghosted → arbitrator + buyer sig → payout to seller */
    RELEASE_TO_SELLER,
    /** Buyer didn't pay → arbitrator + seller sig → refund to buyer */
    REFUND_TO_BUYER
}
