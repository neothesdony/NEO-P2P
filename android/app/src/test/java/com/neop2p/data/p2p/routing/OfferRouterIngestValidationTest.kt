package com.neop2p.data.p2p.routing

import com.neop2p.data.p2p.IdentityManager
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C5/D8 + C10/I6 (2026-09-01): field-level offer ingest gate and nickname
 * sanitization. Pure-function tests mirroring OfferRouter.isValidOfferPayload
 * and IdentityManager.sanitizeNickname.
 */
class OfferRouterIngestValidationTest {

    private fun payload(
        sats: Long,
        fiat: Long,
        price: Double,
        methods: List<String> = listOf("bca"),
        network: String? = null,
    ): String {
        val net = if (network != null) ",\"network\":\"$network\"" else ""
        return """{"offer_id":"offer_x","creator_peer_id":"peerA","type":"SELL","crypto_amount_sats":$sats,"fiat_amount":$fiat,"price_per_unit":$price,"fiat_methods":${Json.encodeToString(ListSerializer(JsonPrimitive.serializer()), methods.map { JsonPrimitive(it) })},"created_at":0$net}"""
    }

    private fun valid(s: String, localNetwork: String = "testnet"): Boolean {
        val obj = Json.parseToJsonElement(s).jsonObject
        return OfferRouter.isValidOfferPayload(obj, localNetwork)
    }

    @Test
    fun `valid offer ingests`() {
        assertTrue(valid(payload(50_000L, 5_000_000L, 20_000_000.0)))
    }

    @Test
    fun `crypto sats out of range rejected`() {
        // Long.MAX_VALUE overflow attempt must not reach money math.
        assertFalse(valid(payload(Long.MAX_VALUE, 1_000_000L, 20_000_000.0)))
        assertFalse(valid(payload(-1L, 1_000_000L, 20_000_000.0)))
        assertFalse(valid(payload(0L, 1_000_000L, 20_000_000.0)))
        assertFalse(valid(payload(1_000_000_000L, 1_000_000L, 20_000_000.0)))
    }

    @Test
    fun `fiat amount out of range rejected`() {
        assertFalse(valid(payload(50_000L, 0L, 20_000_000.0)))
        assertFalse(valid(payload(50_000L, -5L, 20_000_000.0)))
        assertFalse(valid(payload(50_000L, 4_999_999L, 20_000_000.0)))
        assertFalse(valid(payload(50_000L, Long.MAX_VALUE, 20_000_000.0)))
    }

    @Test
    fun `price non finite zero or negative rejected`() {
        assertFalse(valid(payload(50_000L, 1_000_000L, 0.0)))
        assertFalse(valid(payload(50_000L, 1_000_000L, -1.0)))
        assertFalse(valid(payload(50_000L, 1_000_000L, Double.NaN)))
        assertFalse(valid(payload(50_000L, 1_000_000L, Double.POSITIVE_INFINITY)))
        assertFalse(valid(payload(50_000L, 1_000_000L, 1e12)))
    }

    @Test
    fun `fiat methods cardinality and length bounded`() {
        val tooMany = (1..100).map { "method$it" }
        assertFalse(valid(payload(50_000L, 1_000_000L, 20_000_000.0, tooMany)))
        val longMethod = listOf("x".repeat(200))
        assertFalse(valid(payload(50_000L, 1_000_000L, 20_000_000.0, longMethod)))
    }

    @Test
    fun `cross-network offer is rejected`() {
        // testnet local accepts explicit testnet and legacy (missing) payloads,
        // and rejects a mainnet offer.
        assertTrue(valid(payload(50_000L, 5_000_000L, 20_000_000.0, network = "testnet")))
        assertTrue(valid(payload(50_000L, 5_000_000L, 20_000_000.0)))
        assertFalse(valid(payload(50_000L, 5_000_000L, 20_000_000.0, network = "mainnet")))
        // mainnet local accepts only mainnet; legacy/missing and testnet drop.
        assertTrue(
            valid(
                payload(50_000L, 5_000_000L, 20_000_000.0, network = "mainnet"),
                localNetwork = "mainnet",
            )
        )
        assertFalse(
            valid(
                payload(50_000L, 5_000_000L, 20_000_000.0, network = "testnet"),
                localNetwork = "mainnet",
            )
        )
        assertFalse(valid(payload(50_000L, 5_000_000L, 20_000_000.0), localNetwork = "mainnet"))
    }

    @Test
    fun `malformed json does not throw`() {
        val obj = Json.parseToJsonElement("""{"offer_id":"offer_x"}""").jsonObject
        // Missing money fields → null → reject cleanly.
        assertFalse(OfferRouter.isValidOfferPayload(obj))
    }

    @Test
    fun `nickname sanitize strips controls and caps length`() {
        assertEquals("Alice", IdentityManager.sanitizeNickname(" Alice\n\r"))
        val long = "A".repeat(300) + "\n" + "B".repeat(10)
        val sanitized = IdentityManager.sanitizeNickname(long)
        assertEquals(32, sanitized.length)
        assertTrue(sanitized.none { it.isISOControl() })
        assertEquals("", IdentityManager.sanitizeNickname("\u0000\u0001\u0002"))
    }
}
