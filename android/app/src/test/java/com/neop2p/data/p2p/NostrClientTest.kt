package com.neop2p.data.p2p

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class NostrClientTest {

    private fun buildUnsignedEvent(
        pubkey: String,
        kind: Int,
        content: String,
        tags: List<List<String>> = emptyList(),
        createdAt: Long = System.currentTimeMillis() / 1000
    ): JsonObject {
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
                serialized.toString().encodeToByteArray()
            )
        )

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
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `event ID is deterministic for same inputs`() {
        val event1 = buildUnsignedEvent(
            pubkey = "a".repeat(64),
            kind = 33333,
            content = """{"amount":"0.01","price":"15000000"}""",
            createdAt = 1234567890
        )
        val event2 = buildUnsignedEvent(
            pubkey = "a".repeat(64),
            kind = 33333,
            content = """{"amount":"0.01","price":"15000000"}""",
            createdAt = 1234567890
        )
        assertEquals(event1["id"]!!.jsonPrimitive.content, event2["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `different content produces different event IDs`() {
        val event1 = buildUnsignedEvent(
            pubkey = "a".repeat(64), kind = 33333,
            content = "buy 0.01 BTC", createdAt = 1000
        )
        val event2 = buildUnsignedEvent(
            pubkey = "a".repeat(64), kind = 33333,
            content = "sell 0.01 BTC", createdAt = 1000
        )
        assertNotEquals(event1["id"]!!.jsonPrimitive.content, event2["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `event ID is 64 character hex string`() {
        val event = buildUnsignedEvent(
            pubkey = "a".repeat(64), kind = 1, content = "test"
        )
        val id = event["id"]!!.jsonPrimitive.content
        assertEquals(64, id.length)
        assertTrue(id.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `serialized event JSON can be parsed back`() {
        val event = buildUnsignedEvent(
            pubkey = "a".repeat(64), kind = 33333,
            content = """{"amount":"0.5"}""",
            createdAt = 5000
        )
        val jsonStr = event.toString()
        val parsed = Json.parseToJsonElement(jsonStr).jsonObject
        assertEquals(event["id"]!!.jsonPrimitive.content, parsed["id"]!!.jsonPrimitive.content)
        assertEquals(event["pubkey"]!!.jsonPrimitive.content, parsed["pubkey"]!!.jsonPrimitive.content)
        assertEquals(event["kind"]!!.jsonPrimitive.int, parsed["kind"]!!.jsonPrimitive.int)
    }

    // ─── P0-3 regression: offers must be signed with the supplied per-trade key ───

    @Test
    fun `event is signed with the supplied per-trade pubkey (P0-3)`() {
        val (privKey, pubKey) = seedKeyPair()
        val event = NostrEventSigner.buildSignedEvent(
            pubkey = pubKey,
            kind = 33333,
            content = """{"amount":"0.01"}""",
            privateKeyHex = privKey
        )
        // The event must carry the supplied (per-trade) pubkey...
        assertEquals(pubKey, event["pubkey"]!!.jsonPrimitive.content)
        // ...and its signature must verify against that same pubkey.
        assertTrue(NostrEventSigner.verifyEventSignature(event))
    }

    @Test
    fun `event signed with one key fails verification under a different key`() {
        val (privA, pubA) = seedKeyPair()
        val (_, pubB) = seedKeyPair()
        val event = NostrEventSigner.buildSignedEvent(
            pubkey = pubA, kind = 33333,
            content = "{}", privateKeyHex = privA
        )
        // Tamper with the pubkey to simulate signing with the WRONG (identity) key.
        val forged = event.toMutableMap().apply { put("pubkey", JsonPrimitive(pubB)) }
        assertFalse(NostrEventSigner.verifyEventSignature(JsonObject(forged)))
    }

    private fun seedKeyPair(): Pair<String, String> {
        val priv = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val pub = Schnorr.pubKey(priv)
        return priv.joinToString("") { "%02x".format(it) } to
                pub.joinToString("") { "%02x".format(it) }
    }
}
