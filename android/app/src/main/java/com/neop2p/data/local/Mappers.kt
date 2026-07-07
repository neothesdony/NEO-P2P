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
