package com.neop2p.data.portability

import kotlinx.serialization.Serializable

/**
 * Portable identity + trade-state bundle (Phase 3, C5, 2026-09-23). Serialized
 * to JSON by [BundleCodec] and encrypted by [BundleCrypto]. ByteArray columns
 * from the Room entities are carried as Base64 strings by the `:app` mapper so
 * the model stays pure-JVM and serializable.
 *
 * Deliberately excluded: E2EE ratchet state and chat history (moving a ratchet
 * across devices risks message-key reuse; the new device re-handshakes), and
 * dispute records (owned by the local-only :admind daemon).
 */
@Serializable
data class IdentityBundle(
    val version: Int = CURRENT_VERSION,
    val peerId: String,
    val mnemonic: List<String>,
    val nickname: String = "Anonymous",
    val lnNodeId: String = "",
    val walletExternalPointer: Int = 0,
    val walletChangePointer: Int = 0,
    val escrows: List<BundleEscrow> = emptyList(),
    val offers: List<BundleOffer> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class BundleEscrow(
    val escrowId: String,
    val offerId: String,
    val type: String = "ON_CHAIN",
    val fundingTxId: String? = null,
    val payoutTxId: String? = null,
    val fundingAddress: String? = null,
    val fundingScriptType: String = "LEGACY",
    val redeemScriptHex: String? = null,
    val psbtUnsigned: String? = null,
    val psbtBuyerSigned: String? = null,
    val depositAmountSats: Long,
    val tradeAmountSats: Long,
    val feeAmountSats: Long,
    val networkFeeSats: Long = 0,
    val feeAddress: String,
    val buyerPeerId: String,
    val sellerPeerId: String,
    val buyerPubkeyHex: String? = null,
    val sellerPubkeyHex: String? = null,
    val status: String = "FUNDING",
    val buyerSignature: String? = null,
    val sellerSignature: String? = null,
    val arbitratorSignature: String? = null,
    val arbitratorDecision: String? = null,
    val arbitratorNotes: String? = null,
    val channelPoint: String? = null,
    val createdAt: Long = 0L,
    val fundedAt: Long? = null,
    val releasedAt: Long? = null,
    val paidAt: Long? = null,
    val receiptSentAt: Long? = null,
    val receiptReference: String? = null,
    val requiredConfirmations: Int = 1,
    val fundingVout: Long = 0L,
    val buyerBtcAddress: String? = null,
    val refundDestination: String? = null,
    val sellerRefundAddress: String? = null,
    val fundedAmountSats: Long? = null,
    val sellerRefundAttestation: String? = null,
    val buyerAddressAttestation: String? = null,
    val disputedAt: Long? = null,
    val scriptTemplate: String? = null,
    val cltvLocktime: Long? = null
)

@Serializable
data class BundleOffer(
    val offerId: String,
    val creatorPeerId: String,
    val type: String,
    val asset: String = "BTC",
    val fiatAmount: Long,
    val cryptoAmountSats: Long,
    val pricePerUnit: Double,
    val feePercent: Double = 0.005,
    val feeSats: Long = (cryptoAmountSats * 5) / 1000,
    val fiatMethods: String = "[]",
    val status: String = "OPEN",
    val createdAt: Long = 0L,
    val nostrEventId: String? = null,
    val matchedPeerId: String? = null,
    val btcReceiveAddress: String? = null,
    val paymentDetails: String = "{}",
    val expiresAt: Long? = null,
    val lockedAt: Long? = null,
    val creatorPubkeyHex: String? = null,
    val buyerPubkeyHex: String? = null,
    val buyerAddressAttestation: String? = null
)
