package com.neop2p.data.p2p

import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure, Android-free Nostr NIP-01 event signing.
 *
 * Extracted from [NostrClient] so the signing path can be unit-tested on the
 * JVM (plain JUnit, no Robolectric — `android.util.Log` is a stub there).
 *
 * Event ID = SHA-256(serialized_event), where serialized_event is:
 *   [0, pubkey, created_at, kind, tags, content]
 * Signature = BIP-340 Schnorr over the event ID using the Nostr private key.
 *
 * The caller supplies the exact pubkey/privkey pair to sign with — NostrClient
 * passes a fresh per-trade key (P0-3) so offers are not linkable to the identity.
 */
object NostrEventSigner {

    /** Sign a per-trade event. Returns the full NIP-01 event JSON including id/sig. */
    fun buildSignedEvent(
        pubkey: String,
        kind: Int,
        content: String,
        privateKeyHex: String,
        tags: List<List<String>> = emptyList()
    ): JsonObject {
        val createdAt = System.currentTimeMillis() / 1000

        // NIP-01: event ID is SHA-256 of the serialized event array.
        val serialized = buildJsonArray {
            add(0)
            add(pubkey)
            add(createdAt)
            add(kind)
            add(JsonArray(tags.map { tag -> JsonArray(tag.map { JsonPrimitive(it) }) }))
            add(content)
        }

        val eventId = bytesToHex(
            MessageDigest.getInstance("SHA-256").digest(
                Json.encodeToString(JsonElement.serializer(), serialized).encodeToByteArray()
            )
        )

        val signature = sign(eventId, privateKeyHex)

        return buildJsonObject {
            put("id", eventId)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("kind", kind)
            putJsonArray("tags") {
                tags.forEach { tag ->
                    addJsonArray { tag.forEach { item -> add(item) } }
                }
            }
            put("content", content)
            put("sig", signature)
        }
    }

    /** BIP-340 Schnorr signature over a 32-byte event hash. */
    fun sign(eventIdHex: String, privateKeyHex: String): String {
        val msgBytes = hexToBytes(eventIdHex)
        val privKeyBytes = hexToBytes(privateKeyHex)
        val auxRand = SecureRandom().generateSeed(32)
        val signature = Schnorr.sign(privKeyBytes, msgBytes, auxRand)
        return bytesToHex(signature)
    }

    /** NIP-01 signature verification against an x-only pubkey. Never throws. */
    fun verifyEventSignature(event: JsonObject): Boolean {
        val id = event["id"]?.jsonPrimitive?.content ?: return false
        val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return false
        val sig = event["sig"]?.jsonPrimitive?.content ?: return false
        if (id.length != 64 || pubkey.length != 64 || sig.length != 128) return false
        return try {
            Schnorr.verify(hexToBytes(pubkey), hexToBytes(id), hexToBytes(sig))
        } catch (_: Exception) {
            false
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}
