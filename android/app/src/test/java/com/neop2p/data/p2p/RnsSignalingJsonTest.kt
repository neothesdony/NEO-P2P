package com.neop2p.data.p2p

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression for the 2026-09-14 stuck-CONFIRMING escrow: the hand-built
 * signaling envelopes escaped only `"`, so a raw backslash or control
 * character produced invalid JSON that the counterparty dropped silently
 * (the buyer's payout signature was serialized from raw DER bytes).
 */
class RnsSignalingJsonTest {

    @Test
    fun `jsonEscape keeps hand-built signaling JSON parseable`() {
        val nasty = "quote\" back\\slash\nnewline\ttab\u0002control"
        val json = "{\"v\":\"${RnsSession.jsonEscape(nasty)}\"}"
        val parsed = Json.parseToJsonElement(json).jsonObject["v"]!!.jsonPrimitive.content
        assertEquals(nasty, parsed)
    }

    @Test
    fun `jsonEscape leaves plain hex values unchanged`() {
        assertEquals("30440220225c0a01", RnsSession.jsonEscape("30440220225c0a01"))
    }
}
