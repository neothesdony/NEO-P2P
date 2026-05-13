package com.neop2p.domain.model

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
    val totalDepositSats: Long get() = cryptoAmountSats + feeSats
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
    val type: EscrowType = EscrowType.LIGHTNING,
    val fundingTxId: String? = null,
    val payoutTxId: String? = null,
    val depositAmountSats: Long,
    val tradeAmountSats: Long,
    val feeAmountSats: Long,
    val feeAddress: String = NeoP2PConfig.FEE_WALLET_ADDRESS,
    val buyerPeerId: String,
    val sellerPeerId: String,
    val status: EscrowStatus = EscrowStatus.FUNDING,
    val buyerSignature: ByteArray? = null,
    val sellerSignature: ByteArray? = null,
    val channelPoint: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val releasedAt: Long? = null
)

enum class EscrowType { LIGHTNING }
enum class EscrowStatus {
    FUNDING, FUNDED, SIGNED, RELEASED, DISPUTED, REFUNDED
}
