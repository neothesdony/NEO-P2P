package com.neop2p.data.p2p

import com.neop2p.domain.model.TradeOffer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Compact offer digest for the RNS announce feed (Phase 3).
 *
 * RNS announce appData is capped at ~300 bytes (MTU 500 − header − announce
 * overhead), so the full offer JSON cannot ride the announce. The digest
 * carries only the fields the home feed needs to render a card:
 *
 *   {"v":1,"id":"offer_...","c":"<creatorPeerId>","t":"SELL",
 *    "f":<fiatAmount>,"s":<cryptoAmountSats>,"p":<pricePerUnit>,
 *    "m":["bca"],"n":"<nickname>","x":<expiresAt|null>}
 *
 * The full offer JSON is fetched on demand over LXMF (offer_request →
 * offer), so the feed stays within announce size limits while the detail
 * screen still gets the complete offer.
 */
object RnsOfferDigest {

    const val VERSION = 1

    /** Encode a [TradeOffer] into the compact digest JSON. */
    fun encode(offer: TradeOffer, nickname: String = ""): String {
        val obj = buildJsonObject {
            put("v", VERSION)
            put("id", offer.offerId)
            put("c", offer.creatorPeerId)
            put("t", offer.type.name)
            put("f", offer.fiatAmount)
            put("s", offer.cryptoAmountSats)
            put("p", offer.pricePerUnit)
            put("m", Json.encodeToString(ListSerializer(JsonPrimitive.serializer()),
                offer.fiatMethods.map { JsonPrimitive(it) }))
            if (nickname.isNotBlank()) put("n", nickname)
            offer.expiresAt?.let { put("x", it) }
        }
        return obj.toString()
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
}
