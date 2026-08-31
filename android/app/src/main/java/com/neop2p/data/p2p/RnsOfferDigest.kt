package com.neop2p.data.p2p

import com.neop2p.domain.model.TradeOffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest

/**
 * Compact offer digest for the RNS announce feed (Phase 3).
 *
 * The digest is a COMMITMENT ONLY:
 *
 *   {"v":1,"id":"offer_...","h":"<sha256 hex of the canonical offer JSON>"}
 *
 * It deliberately carries NO trade data (amounts, methods, nickname, peerId).
 * RNS announces are broadcast in cleartext to every peer on the mesh and to
 * the transport node, so any field here leaks trading intent (G1). The full
 * public offer subset is fetched on demand over encrypted LXMF
 * (offer_request → offer) and verified against the commitment before ingest,
 * so a peer cannot announce one offer and serve a different one.
 *
 * The canonical JSON (see [canonicalJson]) is the exact payload served over
 * LXMF — the serving side must use [canonicalJson] so the hash matches.
 */
object RnsOfferDigest {

    const val VERSION = 1

    /** Encode a [TradeOffer] into the commitment-only announce digest. */
    fun encode(offer: TradeOffer, nickname: String = ""): String {
        val hash = sha256Hex(canonicalJson(offer, nickname))
        return buildJsonObject {
            put("v", VERSION)
            put("id", offer.offerId)
            put("h", hash)
        }.toString()
    }

    /**
     * Canonical JSON of the public offer subset. This is BOTH the input to
     * the digest commitment AND the payload served over LXMF in response to
     * an offer_request — the two must be byte-identical for the commitment
     * to verify. Payment details and the BTC receive address are LOCAL-ONLY
     * (P0-1) and never appear here.
     */
    fun canonicalJson(offer: TradeOffer, nickname: String = ""): String = buildJsonObject {
        put("offer_id", offer.offerId)
        put("creator_peer_id", offer.creatorPeerId)
        put("type", offer.type.name)
        put("fiat_amount", offer.fiatAmount)
        put("crypto_amount_sats", offer.cryptoAmountSats)
        put("price_per_unit", offer.pricePerUnit)
        put("fee_percent", offer.feePercent)
        put("status", offer.status.name)
        put("created_at", offer.createdAt)
        offer.expiresAt?.let { put("expires_at", it) }
        putJsonArray("fiat_methods") {
            offer.fiatMethods.forEach { add(JsonPrimitive(it)) }
        }
        if (nickname.isNotBlank()) put("nickname", nickname)
    }.toString()

    /** Verify a served offer JSON against the digest commitment. */
    fun verify(offerJson: String, digest: JsonObject): Boolean {
        val expected = digest["h"]?.jsonPrimitive?.content ?: return false
        return sha256Hex(offerJson) == expected
    }

    /** Parse a digest JSON into a [JsonObject] (null when malformed). */
    fun decode(digestJson: String): JsonObject? = try {
        val obj = Json.parseToJsonElement(digestJson).jsonObject
        if (obj["v"]?.jsonPrimitive?.content != VERSION.toString()) null else obj
    } catch (_: Exception) {
        null
    }

    /** The offer id carried by a digest, or null. */
    fun offerIdOf(digest: JsonObject): String? =
        digest["id"]?.jsonPrimitive?.content

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
