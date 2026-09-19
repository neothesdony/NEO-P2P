package com.neop2p.data.wallet

import com.neop2p.data.escrow.ChainMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletFeePolicyTierTest {

    private fun estimate(fast: Long, half: Long, hour: Long) =
        ChainMonitor.FeeEstimate(fastest = fast, halfHour = half, hour = hour)

    @Test
    fun `each tier maps to its field`() {
        val e = estimate(50L, 30L, 20L)
        assertEquals(50L, WalletFeePolicy.rateFor(e, WalletFeePolicy.FeeTier.FAST, null))
        assertEquals(30L, WalletFeePolicy.rateFor(e, WalletFeePolicy.FeeTier.MEDIUM, null))
        assertEquals(20L, WalletFeePolicy.rateFor(e, WalletFeePolicy.FeeTier.SLOW, null))
    }

    @Test
    fun `custom is clamped into one to five hundred`() {
        val e = estimate(50L, 30L, 20L)
        assertEquals(1L, WalletFeePolicy.rateFor(e, WalletFeePolicy.FeeTier.CUSTOM, 0L))
        assertEquals(1L, WalletFeePolicy.rateFor(e, WalletFeePolicy.FeeTier.CUSTOM, -5L))
        assertEquals(500L, WalletFeePolicy.rateFor(e, WalletFeePolicy.FeeTier.CUSTOM, 10_000L))
        assertEquals(123L, WalletFeePolicy.rateFor(e, WalletFeePolicy.FeeTier.CUSTOM, 123L))
    }

    @Test
    fun `a null custom rate never escapes`() {
        val e = estimate(50L, 30L, 20L)
        val rate = WalletFeePolicy.rateFor(e, WalletFeePolicy.FeeTier.CUSTOM, null)
        assertEquals(30L, rate)
        assertTrue(rate >= 1L)
    }

    @Test
    fun `non-monotonic input is normalized`() {
        // fastest = 1 next to halfHour = 30 is reachable from independent clamping.
        val normalized = WalletFeePolicy.normalizeMonotonic(estimate(1L, 30L, 20L))
        assertTrue(normalized.fastest > normalized.halfHour)
        assertTrue(normalized.halfHour >= normalized.hour)
        // hour > halfHour must also be fixed.
        val normalized2 = WalletFeePolicy.normalizeMonotonic(estimate(50L, 10L, 40L))
        assertTrue(normalized2.halfHour >= normalized2.hour)
        assertTrue(normalized2.fastest > normalized2.halfHour)
    }

    @Test
    fun `normalization keeps every rate inside the clamp`() {
        val normalized = WalletFeePolicy.normalizeMonotonic(estimate(9_999L, 0L, 9_999L))
        assertTrue(normalized.fastest in 1L..WalletFeePolicy.MAX_FEE_RATE_SAT_VB)
        assertTrue(normalized.halfHour in 1L..WalletFeePolicy.MAX_FEE_RATE_SAT_VB)
        assertTrue(normalized.hour in 1L..WalletFeePolicy.MAX_FEE_RATE_SAT_VB)
    }

    @Test
    fun `rateFor normalizes before selecting`() {
        val e = estimate(1L, 30L, 20L)
        assertTrue(WalletFeePolicy.rateFor(e, WalletFeePolicy.FeeTier.FAST, null) > 30L)
        assertEquals(30L, WalletFeePolicy.rateFor(e, WalletFeePolicy.FeeTier.MEDIUM, null))
    }
}
