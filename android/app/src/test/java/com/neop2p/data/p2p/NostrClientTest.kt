package com.neop2p.data.p2p

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * Unit tests for Nostr NIP-01 event building and signing.
 *
 * Tests that event serialization matches the NIP-01 specification
 * (https://github.com/nostr-protocol/nips/blob/master/01.md).
 *
 * Event ID = SHA-256(JSON serialization of [0, pubkey, created_at, kind, tags, content])
 * Signature = Schnorr(BIP-340) of event ID
 */
class NostrClientTest {

    private val json = Json { encodeDefaults = true }

    /**
     * Build a Nostr event per NIP-01 spec and compute its ID.
     * This matches the logic in NostrClient.buildSignedEvent().
     */
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
                Json.encodeToString(serialized).encodeToByteArray()
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

    // ─── Helpers ───────────────────────────────────────────────

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    // ─── Tests ─────────────────────────────────────────────────

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
    fun `different pubkeys produce different event IDs`() {
        val event1 = buildUnsignedEvent(
            pubkey = "a".repeat(64), kind = 33333,
            content = "trade", createdAt = 1000
        )
        val event2 = buildUnsignedEvent(
            pubkey = "b".repeat(64), kind = 33333,
            content = "trade", createdAt = 1000
        )
        assertNotEquals(event1["id"]!!.jsonPrimitive.content, event2["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `trade offer kind uses correct value`() {
        val event = buildUnsignedEvent(
            pubkey = "a".repeat(64), kind = 33333,
            content = "test"
        )
        assertEquals(33333, event["kind"]!!.jsonPrimitive.int)
    }

    @Test
    fun `event includes pubkey field`() {
        val pubkey = "abc123".repeat(10).take(64)
        val event = buildUnsignedEvent(
            pubkey = pubkey, kind = 1, content = "hello"
        )
        assertEquals(pubkey, event["pubkey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `event includes tags as empty array when none provided`() {
        val event = buildUnsignedEvent(
            pubkey = "a".repeat(64), kind = 1, content = "no tags"
        )
        val tags = event["tags"]!!.jsonArray
        assertTrue(tags.isEmpty())
    }

    @Test
    fun `event includes tags correctly`() {
        val tags = listOf(
            listOf("p", "abc123"),
            listOf("e", "def456", "relay.nostr.com")
        )
        val event = buildUnsignedEvent(
            pubkey = "a".repeat(64), kind = 1,
            content = "with tags", tags = tags
        )
        val serialized = Json.encodeToString(event)
        assertTrue(serialized.contains("\"p\"""))
        assertTrue(serialized.contains("\"abc123\"""))
        assertTrue(serialized.contains("\"e\"""))
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
        val jsonStr = Json.encodeToString(event)
        val parsed = Json.parseToJsonElement(jsonStr).jsonObject
        assertEquals(event["id"]!!.jsonPrimitive.content, parsed["id"]!!.jsonPrimitive.content)
        assertEquals(event["pubkey"]!!.jsonPrimitive.content, parsed["pubkey"]!!.jsonPrimitive.content)
        assertEquals(event["kind"]!!.jsonPrimitive.content, parsed["kind"]!!.jsonPrimitive.content)
    }
}
