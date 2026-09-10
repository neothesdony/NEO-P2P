package com.neop2p.data.reputation

import com.neop2p.data.p2p.Schnorr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pure-JVM codec for signed peer attestations (no Android imports).
 *
 * Wire format (LXMF DIRECT signaling, title = "attestation",
 * FIELD_CUSTOM_DATA = this JSON):
 *   { "from_peer", "target_peer", "outcome", "volume_sats",
 *     "timestamp", "pubkey", "signature" }
 *
 * The pubkey travels IN the payload because RNS-era peers store
 * `nostr_pubkey=""` (the old verify-against-stored-key path can never
 * pass). Trust binding: the LXMF DIRECT sender identity is authenticated
 * by RNS announce signatures, so `senderPeerId == from_peer` binds the
 * pubkey to the peerId. A stored non-blank pubkey is pinned: a payload
 * carrying a different pubkey is rejected (TOFU-pin).
 */
object AttestationCodec {

    const val PREFIX = "NEOP2P_ATTEST"

    data class AttestationPayload(
        val fromPeer: String,
        val targetPeer: String,
        val outcome: String,          // "POSITIVE" | "NEGATIVE"
        val volumeSats: Long,
        val timestamp: Long,
        val pubkeyHex: String,        // 64 hex chars (x-only secp256k1)
        val signatureHex: String      // 128 hex chars (BIP-340)
    )

    enum class AttestationValidation { OK, WRONG_SENDER, SELF_RATING, KEY_MISMATCH }

    /** Canonical signed bytes — the legacy format, unchanged. */
    fun canonicalData(
        fromPeer: String,
        targetPeer: String,
        outcome: String,
        volumeSats: Long,
        timestamp: Long
    ): ByteArray =
        "$PREFIX:$fromPeer:$targetPeer:$outcome:$volumeSats:$timestamp".encodeToByteArray()

    fun buildPayload(
        fromPeer: String,
        targetPeer: String,
        outcome: String,
        volumeSats: Long,
        timestamp: Long,
        pubkeyHex: String,
        signatureHex: String
    ): String = buildJsonObject {
        put("from_peer", fromPeer)
        put("target_peer", targetPeer)
        put("outcome", outcome)
        put("volume_sats", volumeSats)
        put("timestamp", timestamp)
        put("pubkey", pubkeyHex)
        put("signature", signatureHex)
    }.toString()

    fun parsePayload(json: String): AttestationPayload? = try {
        val obj: JsonObject = Json.parseToJsonElement(json).jsonObject
        AttestationPayload(
            fromPeer = obj["from_peer"]?.jsonPrimitive?.content ?: return null,
            targetPeer = obj["target_peer"]?.jsonPrimitive?.content ?: return null,
            outcome = obj["outcome"]?.jsonPrimitive?.content ?: return null,
            volumeSats = obj["volume_sats"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null,
            timestamp = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null,
            pubkeyHex = obj["pubkey"]?.jsonPrimitive?.content ?: return null,
            signatureHex = obj["signature"]?.jsonPrimitive?.content ?: return null
        )
    } catch (e: Exception) {
        null
    }

    fun signatureHex(signature: ByteArray): String =
        signature.joinToString("") { "%02x".format(it) }

    fun verify(pubkeyHex: String, data: ByteArray, signatureHex: String): Boolean = try {
        if (pubkeyHex.length != 64 || signatureHex.length != 128) return false
        Schnorr.verify(hexToBytes(pubkeyHex), data, hexToBytes(signatureHex))
    } catch (e: Exception) {
        false
    }

    /**
     * Security rules, pure so they are unit-testable:
     * - the LXMF sender must BE the signer (sender-authenticated ingest)
     * - nobody rates themselves on the wire
     * - a stored non-blank pubkey is pinned; a blank one is adoptable
     */
    fun validate(
        payload: AttestationPayload,
        senderPeerId: String,
        storedPubkeyHex: String?
    ): AttestationValidation = when {
        senderPeerId != payload.fromPeer -> AttestationValidation.WRONG_SENDER
        payload.fromPeer == payload.targetPeer -> AttestationValidation.SELF_RATING
        !storedPubkeyHex.isNullOrBlank() && storedPubkeyHex != payload.pubkeyHex ->
            AttestationValidation.KEY_MISMATCH
        else -> AttestationValidation.OK
    }

    internal fun hexToBytes(hex: String): ByteArray {
        val data = ByteArray(hex.length / 2)
        for (i in 0 until hex.length step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}
