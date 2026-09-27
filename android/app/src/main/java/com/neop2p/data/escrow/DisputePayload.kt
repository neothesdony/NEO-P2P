package com.neop2p.data.escrow

import com.neop2p.data.local.PendingDisputeStore
import com.neop2p.domain.model.Escrow

/**
 * Single source for the LXMF `dispute` wire map. The manual dispute
 * (`EscrowViewModel.disputeEscrow`), the automatic sweep escalation
 * (`EscrowService.escalateToDispute`) and the 60s retry all use this, so the
 * copies cannot drift apart again — that drift is exactly what left every
 * auto-dispute without a `refund_tx_hex` (2026-09-26,
 * `escrow_offer_1790376777696_1790381499906`), which the arbitrator then
 * refused to rule.
 *
 * The persisted pending value wins; [local] (the live escrow) enriches legacy
 * rows and supplies the fields the pending row does not carry (redeem-script
 * template + V1 maturity, the parties).
 */
fun PendingDisputeStore.PendingDispute.toWireFields(local: Escrow? = null): Map<String, String> = buildMap {
    redeemScriptHex?.let { put("redeem_script_hex", it) }
    psbtHex?.let { put("psbt_hex", it) }
    refundTxHex?.let { put("refund_tx_hex", it) }
    depositSats?.let { put("deposit_sats", it.toString()) }
    fundingScriptType?.let { put("funding_script_type", it) }
    // The persisted outpoint first, live escrow fallback (legacy rows have neither).
    (fundingTxid ?: local?.fundingTxId)?.let { put("funding_txid", it) }
    (fundingVout ?: local?.fundingVout)?.let { put("funding_vout", it.toString()) }
    // C9: the redeem-script template + V1 maturity so the arbitrator gates and
    // resolves the right script shape.
    local?.scriptTemplate?.let { put("script_template", it.id) }
    local?.cltvLocktime?.let { put("cltv_locktime", it.toString()) }
    sellerRefundAddress?.let { put("seller_refund_address", it) }
    // F2 (2026-09-12): role keys + role-signed destination attestations
    // (persisted value first, live escrow fallback for legacy rows).
    (offerId ?: local?.offerId)?.let { put("offer_id", it) }
    (buyerBtcAddress ?: local?.buyerBtcAddress)?.takeIf { it.isNotBlank() }
        ?.let { put("buyer_btc_address", it) }
    (buyerPubKeyHex ?: local?.buyerPubKeyHex)?.let { put("buyer_pubkey_hex", it) }
    (sellerPubKeyHex ?: local?.sellerPubKeyHex)?.let { put("seller_pubkey_hex", it) }
    (tradeSats ?: local?.tradeAmountSats)?.let { put("trade_sats", it.toString()) }
    (sellerRefundAttestation ?: local?.sellerRefundAttestation)
        ?.let { put("seller_refund_attestation", it) }
    (buyerAddressAttestation ?: local?.buyerAddressAttestation)
        ?.let { put("buyer_address_attestation", it) }
    // v23 (2026-09-02): the parties so the arbitrator — with NO local escrow row —
    // can deliver the resolution to the buyer AND seller.
    local?.let {
        put("buyer_peer_id", it.buyerPeerId)
        put("seller_peer_id", it.sellerPeerId)
    }
}

/**
 * Assemble the dispute payload from an escrow + its two unsigned tx hexes.
 * Pure so the "both txs are shipped" contract is unit-tested independently of
 * the DB/chain the builders need.
 */
fun assembleDisputePending(
    escrow: Escrow,
    openedBy: String,
    reason: String,
    psbtHex: String?,
    refundTxHex: String?,
): PendingDisputeStore.PendingDispute = PendingDisputeStore.PendingDispute(
    escrowId = escrow.escrowId,
    openedBy = openedBy,
    reason = reason,
    redeemScriptHex = escrow.redeemScriptHex,
    psbtHex = psbtHex,
    refundTxHex = refundTxHex,
    // The ACTUAL on-chain funding value: the arbitrator signs the rich refund.
    depositSats = escrow.fundedAmountSats ?: escrow.depositAmountSats,
    fundingScriptType = escrow.fundingScriptType.name,
    fundingTxid = escrow.fundingTxId,
    fundingVout = escrow.fundingVout.toInt(),
    sellerRefundAddress = escrow.sellerRefundAddress,
    offerId = escrow.offerId,
    buyerBtcAddress = escrow.buyerBtcAddress,
    buyerPubKeyHex = escrow.buyerPubKeyHex,
    sellerPubKeyHex = escrow.sellerPubKeyHex,
    tradeSats = escrow.tradeAmountSats,
    sellerRefundAttestation = escrow.sellerRefundAttestation,
    buyerAddressAttestation = escrow.buyerAddressAttestation,
)
