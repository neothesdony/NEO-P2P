package com.neop2p.data.local

import com.neop2p.data.local.entity.*
import com.neop2p.domain.model.*

/** Convert Room entity → domain model */
fun TradeOfferEntity.toDomain(): TradeOffer = TradeOffer(
    offerId = offer_id,
    creatorPeerId = creator_peer_id,
    type = OfferType.valueOf(type),
    asset = CryptoAsset.valueOf(asset),
    fiatAmount = fiat_amount,
    cryptoAmountSats = crypto_amount_sats,
    pricePerUnit = price_per_unit,
    feePercent = fee_percent,
    feeSats = fee_sats,
    fiatMethods = parseJsonStringList(fiat_methods),
    status = OfferStatus.valueOf(status),
    createdAt = created_at,
    nostrEventId = nostr_event_id
)

/** Convert domain model → Room entity */
fun TradeOffer.toEntity(): TradeOfferEntity = TradeOfferEntity(
    offer_id = offerId,
    creator_peer_id = creatorPeerId,
    type = type.name,
    asset = asset.name,
    fiat_amount = fiatAmount,
    crypto_amount_sats = cryptoAmountSats,
    price_per_unit = pricePerUnit,
    fee_percent = feePercent,
    fee_sats = feeSats,
    fiat_methods = toJsonStringList(fiatMethods),
    status = status.name,
    created_at = createdAt,
    nostr_event_id = nostrEventId
)

fun PeerEntity.toDomain(): Peer = Peer(
    peerId = peer_id,
    nickname = nickname,
    nostrPubkey = nostr_pubkey,
    lnNodeId = ln_node_id,
    createdAt = created_at,
    reputationScore = reputation_score,
    totalTrades = total_trades,
    lastSeen = last_seen,
    relayHints = parseJsonStringList(relay_hints),
    multiaddrs = parseJsonStringList(multiaddrs)
)

fun Peer.toEntity(): PeerEntity = PeerEntity(
    peer_id = peerId,
    nickname = nickname,
    nostr_pubkey = nostrPubkey,
    ln_node_id = lnNodeId,
    created_at = createdAt,
    reputation_score = reputationScore,
    total_trades = totalTrades,
    last_seen = lastSeen,
    relay_hints = toJsonStringList(relayHints),
    multiaddrs = toJsonStringList(multiaddrs)
)

private fun parseJsonStringList(json: String): List<String> =
    try {
        kotlinx.serialization.json.Json.decodeFromString<List<String>>(json)
    } catch (_: Exception) {
        if (json.isBlank() || json == "[]") emptyList()
        else listOf(json.trim('"'))
    }

private fun toJsonStringList(list: List<String>): String =
    // Manual JSON array serialization without kotlinx serialization plugin
    try {
        "[" + list.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
    } catch (_: Exception) {
        "[]"
    }

// ─── Escrow mappers ───────────────────────────────────────────────

fun EscrowEntity.toDomain(): Escrow = Escrow(
    escrowId = escrow_id,
    offerId = offer_id,
    type = EscrowType.valueOf(type),
    fundingTxId = funding_tx_id,
    payoutTxId = payout_tx_id,
    fundingAddress = funding_address,
    fundingAddressPath = funding_address_path,
    redeemScriptHex = redeem_script_hex,
    psbtUnsigned = psbt_unsigned,
    psbtBuyerSigned = psbt_buyer_signed,
    depositAmountSats = deposit_amount_sats,
    tradeAmountSats = trade_amount_sats,
    feeAmountSats = fee_amount_sats,
    networkFeeSats = network_fee_sats,
    feeAddress = fee_address,
    buyerPeerId = buyer_peer_id,
    sellerPeerId = seller_peer_id,
    buyerPubKeyHex = buyer_pubkey_hex,
    sellerPubKeyHex = seller_pubkey_hex,
    status = EscrowStatus.valueOf(status),
    buyerSignature = buyer_signature,
    sellerSignature = seller_signature,
    arbitratorSignature = arbitrator_signature,
    arbitratorDecision = arbitrator_decision,
    arbitratorNotes = arbitrator_notes,
    channelPoint = channel_point,
    createdAt = created_at,
    fundedAt = funded_at,
    releasedAt = released_at
)

fun Escrow.toEntity(): EscrowEntity = EscrowEntity(
    escrow_id = escrowId,
    offer_id = offerId,
    type = type.name,
    funding_tx_id = fundingTxId,
    payout_tx_id = payoutTxId,
    funding_address = fundingAddress,
    funding_address_path = fundingAddressPath,
    redeem_script_hex = redeemScriptHex,
    psbt_unsigned = psbtUnsigned,
    psbt_buyer_signed = psbtBuyerSigned,
    deposit_amount_sats = depositAmountSats,
    trade_amount_sats = tradeAmountSats,
    fee_amount_sats = feeAmountSats,
    network_fee_sats = networkFeeSats,
    fee_address = feeAddress,
    buyer_peer_id = buyerPeerId,
    seller_peer_id = sellerPeerId,
    buyer_pubkey_hex = buyerPubKeyHex,
    seller_pubkey_hex = sellerPubKeyHex,
    status = status.name,
    buyer_signature = buyerSignature,
    seller_signature = sellerSignature,
    arbitrator_signature = arbitratorSignature,
    arbitrator_decision = arbitratorDecision,
    arbitrator_notes = arbitratorNotes,
    channel_point = channelPoint,
    created_at = createdAt,
    funded_at = fundedAt,
    released_at = releasedAt
)
