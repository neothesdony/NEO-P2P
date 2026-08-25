package com.neop2p.domain.model

import com.neop2p.NeoP2PConfig

/**
 * On-chain 2-of-3 multisig Bitcoin escrow.
 *
 * The SELLER supplies BTC (deposits `depositAmountSats`) into the P2SH
 * multisig address. The BUYER pays fiat (IDR) out-of-app. Once the funding
 * transaction is confirmed on-chain, the payout sends `tradeAmountSats` to
 * the buyer and `feeAmountSats` to the fee wallet.
 *
 * P0-1 hardening: each escrow stores the exact secp256k1 pubkeys that are
 * allowed to sign for the buyer and seller roles (`buyerPubKeyHex` /
 * `sellerPubKeyHex`). A signature is only accepted if it verifies against the
 * matching role pubkey inside the 2-of-3 redeem script.
 */
data class Escrow(
    val escrowId: String,
    val offerId: String,
    val type: EscrowType = EscrowType.ON_CHAIN,
    val fundingTxId: String? = null,
    val payoutTxId: String? = null,
    val fundingAddress: String? = null,        // 2-of-3 P2SH multisig address
    val fundingAddressPath: String? = null,    // BIP-32 derivation path (unused for P2SH)
    val redeemScriptHex: String? = null,
    val psbtUnsigned: ByteArray? = null,       // serialized unsigned payout tx
    val psbtBuyerSigned: ByteArray? = null,    // reserved for future PSBT flows
    val depositAmountSats: Long,
    val tradeAmountSats: Long,
    val feeAmountSats: Long,
    val networkFeeSats: Long = 0,
    val feeAddress: String = NeoP2PConfig.FEE_WALLET_ADDRESS,
    val buyerPeerId: String,
    val sellerPeerId: String,
    // P0-1: exact pubkeys authorized to sign for each role.
    val buyerPubKeyHex: String? = null,
    val sellerPubKeyHex: String? = null,
    val status: EscrowStatus = EscrowStatus.FUNDING,
    val buyerSignature: ByteArray? = null,
    val sellerSignature: ByteArray? = null,
    val arbitratorSignature: ByteArray? = null,
    val arbitratorDecision: String? = null,
    val arbitratorNotes: String? = null,
    val channelPoint: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    // When the funding tx was confirmed on-chain (status transitioned FUNDING→FUNDED).
    // Used to measure the auto-refund timeout from confirmation, not from creation.
    val fundedAt: Long? = null,
    val releasedAt: Long? = null,
    // When the BUYER marked the fiat payment as sent (status PAID). Starts the
    // payment window: if the seller neither releases nor disputes before the
    // window expires, the escrow auto-transitions to DISPUTED (never silently
    // auto-refunds — the buyer may have actually paid).
    val paidAt: Long? = null,
    // On-chain confirmations required before a funding tx is accepted (1+).
    // Mirrors HodlHodl's configurable-confirmations model.
    val requiredConfirmations: Int = 1
)

enum class EscrowType { ON_CHAIN }

enum class EscrowStatus {
    FUNDING, FUNDED, SIGNED, PAID, RELEASED, DISPUTED, RESOLVING, CANCELLED, REFUNDED
}

enum class ResolutionDecision {
    /** Trade completed → payout tx broadcasts tradeAmountSats to the BUYER + fee to the wallet. */
    RELEASE_TO_BUYER,
    /** Buyer did not pay → refund tx returns the deposit to the SELLER. */
    REFUND_TO_SELLER
}
