package com.neop2p.data.local

import com.neop2p.data.local.entity.*
import com.neop2p.domain.model.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    nostrEventId = nostr_event_id,
    matchedPeerId = matched_peer_id,
    btcReceiveAddress = btc_receive_address ?: "",
    paymentDetails = parsePaymentDetails(payment_details),
    expiresAt = expires_at,
    lockedAt = locked_at,
    creatorPubKeyHex = creator_pubkey_hex ?: "",
    buyerPubKeyHex = buyer_pubkey_hex,
    buyerAddressAttestation = buyer_address_attestation,
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
    nostr_event_id = nostrEventId,
    matched_peer_id = matchedPeerId,
    btc_receive_address = btcReceiveAddress.takeIf { it.isNotBlank() },
    payment_details = toPaymentDetailsJson(paymentDetails),
    expires_at = expiresAt,
    locked_at = lockedAt,
    creator_pubkey_hex = creatorPubKeyHex.takeIf { it.isNotBlank() },
    buyer_pubkey_hex = buyerPubKeyHex,
    buyer_address_attestation = buyerAddressAttestation,
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

// ─── Payment details mappers (bank number + holder name per method) ───────

/** Parse the stored JSON {"method":{"accountNumber":..,"accountHolder":..}}. */
internal fun parsePaymentDetails(json: String): Map<String, PaymentDetails> =
    try {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        obj.mapValues { (_, v) ->
            val method = v.jsonObject
            PaymentDetails(
                accountNumber = method["accountNumber"]?.jsonPrimitive?.content ?: "",
                accountHolder = method["accountHolder"]?.jsonPrimitive?.content ?: "",
                qrisString = method["qrisString"]?.jsonPrimitive?.content ?: ""
            )
        }
    } catch (_: Exception) {
        emptyMap()
    }

internal fun toPaymentDetailsJson(details: Map<String, PaymentDetails>): String =
    try {
        val root = org.json.JSONObject()
        details.forEach { (method, d) ->
            root.put(
                method,
                org.json.JSONObject()
                    .put("accountNumber", d.accountNumber)
                    .put("accountHolder", d.accountHolder)
                    .put("qrisString", d.qrisString)
            )
        }
        root.toString()
    } catch (_: Exception) {
        "{}"
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
    fundingScriptType = try {
        BitcoinAddressType.valueOf(funding_script_type)
    } catch (_: Exception) {
        BitcoinAddressType.LEGACY
    },
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
    releasedAt = released_at,
    paidAt = paid_at,
    receiptSentAt = receipt_sent_at,
    receiptReference = receipt_reference,
    requiredConfirmations = required_confirmations,
    fundingVout = funding_vout,
    buyerBtcAddress = buyer_btc_address,
    refundDestination = refund_destination,
    sellerRefundAddress = seller_refund_address,
    fundedAmountSats = funded_amount_sats,
    sellerRefundAttestation = seller_refund_attestation,
    buyerAddressAttestation = buyer_address_attestation
)

fun Escrow.toEntity(): EscrowEntity = EscrowEntity(
    escrow_id = escrowId,
    offer_id = offerId,
    type = type.name,
    funding_tx_id = fundingTxId,
    payout_tx_id = payoutTxId,
    funding_address = fundingAddress,
    funding_address_path = fundingAddressPath,
    funding_script_type = fundingScriptType.name,
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
    released_at = releasedAt,
    paid_at = paidAt,
    receipt_sent_at = receiptSentAt,
    receipt_reference = receiptReference,
    required_confirmations = requiredConfirmations,
    funding_vout = fundingVout,
    buyer_btc_address = buyerBtcAddress,
    refund_destination = refundDestination,
    seller_refund_address = sellerRefundAddress,
    funded_amount_sats = fundedAmountSats,
    seller_refund_attestation = sellerRefundAttestation,
    buyer_address_attestation = buyerAddressAttestation
)
