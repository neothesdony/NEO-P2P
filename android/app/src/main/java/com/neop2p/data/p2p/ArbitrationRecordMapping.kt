package com.neop2p.data.p2p

import com.neop2p.data.local.entity.ArbitratorDisputeEntity

/**
 * Room ↔ canonical arbitration-record mapping. The canonical shape lives in
 * `:core` ([DisputeRecord]) so the headless arbitrator daemon persists the
 * same row the app does.
 */

internal fun ArbitratorDisputeEntity.toRecord(): DisputeRecord = DisputeRecord(
    escrowId = escrow_id,
    openedBy = opened_by,
    reason = reason,
    openedAt = opened_at,
    redeemScriptHex = redeem_script_hex,
    psbtHex = psbt_hex,
    refundTxHex = refund_tx_hex,
    depositSats = deposit_sats,
    fundingScriptType = funding_script_type,
    sellerRefundAddress = seller_refund_address,
    buyerPeerId = buyer_peer_id,
    sellerPeerId = seller_peer_id,
    buyerBtcAddress = buyer_btc_address,
    buyerPubkeyHex = buyer_pubkey_hex,
    sellerPubkeyHex = seller_pubkey_hex,
    sellerRefundAttestation = seller_refund_attestation,
    buyerAddressAttestation = buyer_address_attestation,
    offerId = offer_id,
    tradeSats = trade_sats,
    receivedAt = received_at,
    resolved = resolved,
)

internal fun DisputeRecord.toEntity(): ArbitratorDisputeEntity = ArbitratorDisputeEntity(
    escrow_id = escrowId,
    opened_by = openedBy,
    reason = reason,
    opened_at = openedAt,
    redeem_script_hex = redeemScriptHex,
    psbt_hex = psbtHex,
    refund_tx_hex = refundTxHex,
    deposit_sats = depositSats,
    funding_script_type = fundingScriptType,
    seller_refund_address = sellerRefundAddress,
    buyer_peer_id = buyerPeerId,
    seller_peer_id = sellerPeerId,
    buyer_btc_address = buyerBtcAddress,
    buyer_pubkey_hex = buyerPubkeyHex,
    seller_pubkey_hex = sellerPubkeyHex,
    seller_refund_attestation = sellerRefundAttestation,
    buyer_address_attestation = buyerAddressAttestation,
    offer_id = offerId,
    trade_sats = tradeSats,
    received_at = receivedAt,
    resolved = resolved,
)
