package com.neop2p.data.escrow

import com.neop2p.domain.model.BitcoinAddressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArbitrationFundingTest {

    @Test
    fun `refund vsize is the full multisig spend plus a legacy output`() {
        assertEquals(264L, ArbitrationFunding.refundVsize(BitcoinAddressType.LEGACY))
        assertEquals(148L, ArbitrationFunding.refundVsize(BitcoinAddressType.SEGWIT))
    }

    @Test
    fun `ceiling is rate-aware with a 4x safety margin`() {
        // 50 sat/vB * 264 vB * 4 = 52_800; burn cap = 250_000 -> rate wins.
        assertEquals(52_800L, ArbitrationFunding.feeCeiling(1_000_000L, 50L, BitcoinAddressType.LEGACY))
    }

    @Test
    fun `burn cap dominates for small deposits`() {
        // 50 * 264 * 4 = 52_800 but funded/4 = 25_000 -> burn cap wins.
        assertEquals(25_000L, ArbitrationFunding.feeCeiling(100_000L, 50L, BitcoinAddressType.LEGACY))
    }

    @Test
    fun `zero rate falls back to the relay floor`() {
        assertEquals(250L, ArbitrationFunding.feeCeiling(100_000L, 0L, BitcoinAddressType.LEGACY))
        assertEquals(250L, ArbitrationFunding.feeCeiling(0L, 50L, BitcoinAddressType.LEGACY))
    }

    @Test
    fun `rate is clamped at the wallet maximum`() {
        // 10_000 sat/vB would be 500-clamped: 500 * 264 * 4 = 528_000, burn cap 250_000.
        assertEquals(250_000L, ArbitrationFunding.feeCeiling(1_000_000L, 10_000L, BitcoinAddressType.LEGACY))
    }

    @Test
    fun `expectation uses the on-chain value and the caller's own rate`() {
        val e = ArbitrationFunding.refundExpectation("bc1qexample", 2_000_000L, 50L, BitcoinAddressType.LEGACY)
        assertEquals("bc1qexample", e.destinationAddress)
        assertEquals(2_000_000L, e.fundedInputSats)
        // 50 * 264 * 4 = 52_800 vs burn cap 500_000 -> 52_800.
        assertEquals(52_800L, e.feeCeilingSats)
    }

    @Test
    fun `script type maps SEGWIT and everything else to legacy`() {
        assertEquals(BitcoinAddressType.SEGWIT, ArbitrationFunding.scriptTypeOf("SEGWIT"))
        assertEquals(BitcoinAddressType.SEGWIT, ArbitrationFunding.scriptTypeOf("segwit"))
        assertEquals(BitcoinAddressType.LEGACY, ArbitrationFunding.scriptTypeOf("P2WSH"))
        assertEquals(BitcoinAddressType.LEGACY, ArbitrationFunding.scriptTypeOf(null))
    }

    @Test
    fun `apply-side ceiling accepts a refund built at a much higher live rate`() {
        // The refund fee is frozen at build time and gated by the arbitrator's
        // own rate at pre-sign; re-deriving the ceiling from a fresh rate at
        // apply time must not refuse a legitimately signed resolution.
        val funded = 5_000_000L
        val builtAt200 = 200L * ArbitrationFunding.refundVsize(BitcoinAddressType.LEGACY) // 52_800
        // A fresh 40 sat/vB ceiling would refuse it (40 * 264 * 4 = 42_240).
        assertTrue(builtAt200 > ArbitrationFunding.feeCeiling(funded, 40L, BitcoinAddressType.LEGACY))
        val applied = ArbitrationFunding.applySideRefundExpectation("bc1qseller", funded, BitcoinAddressType.LEGACY)
        assertTrue(builtAt200 <= applied.feeCeilingSats)
        // The wallet clamp binds: 500 * 264 * 4 = 528_000 (< funded/4 = 1_250_000).
        assertEquals(528_000L, applied.feeCeilingSats)
    }
}
