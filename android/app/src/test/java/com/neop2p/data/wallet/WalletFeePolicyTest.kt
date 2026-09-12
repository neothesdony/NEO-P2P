package com.neop2p.data.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests for the wallet fee policy (audit P1-2 / P3-1, 2026-09-12).
 *
 * The invariant under test: the fee shown in the send-confirm dialog is a
 * CEILING for the fee actually paid. A rate that arrives inflated between the
 * preview and the broadcast must not be spent, and no fee may exceed a fixed
 * share of the amount sent.
 */
class WalletFeePolicyTest {

    @Test
    fun `rate under the ceiling is unchanged`() {
        assertEquals(17L, WalletFeePolicy.clampRate(17L))
    }

    @Test
    fun `rate above the ceiling is clamped`() {
        assertEquals(WalletFeePolicy.MAX_FEE_RATE_SAT_VB, WalletFeePolicy.clampRate(50_000L))
    }

    @Test
    fun `zero or negative rate is raised to one sat per vbyte`() {
        assertEquals(1L, WalletFeePolicy.clampRate(0L))
        assertEquals(1L, WalletFeePolicy.clampRate(-5L))
    }

    @Test
    fun `cap is five percent of the amount sent`() {
        // 100_000 sats * 5% = 5_000, comfortably above the 250 floor.
        assertEquals(5_000L, WalletFeePolicy.maxFeeSats(100_000L))
    }

    @Test
    fun `cap never drops below the fee floor`() {
        // 1_000 sats * 5% = 50 -> floor wins, so a dust-sized send is still relayable.
        assertEquals(WalletFeePolicy.MIN_SEND_FEE_SATS, WalletFeePolicy.maxFeeSats(1_000L))
    }

    @Test
    fun `fee at or under the confirmed ceiling is accepted`() {
        assertNull(WalletFeePolicy.rejectReason(feeSats = 1_430L, amountSats = 100_000L, confirmedMaxFeeSats = 1_430L))
        assertNull(WalletFeePolicy.rejectReason(feeSats = 1_430L, amountSats = 100_000L, confirmedMaxFeeSats = null))
    }

    @Test
    fun `fee above the confirmed ceiling is rejected with a re-confirm message`() {
        val reason = WalletFeePolicy.rejectReason(feeSats = 90_000L, amountSats = 100_000L, confirmedMaxFeeSats = 1_430L)
        assertNotNull(reason)
        assert(reason!!.contains("1,430") || reason.contains("1430"))
        assert(reason.contains("90,000") || reason.contains("90000"))
    }

    @Test
    fun `fee above five percent is rejected even with no confirmed ceiling`() {
        val reason = WalletFeePolicy.rejectReason(feeSats = 6_000L, amountSats = 100_000L, confirmedMaxFeeSats = null)
        assertNotNull(reason)
    }

    @Test
    fun `free-fee preview is not a ceiling of zero`() {
        // A failed preview (null) must not block the send; the 5% cap still applies.
        assertNull(WalletFeePolicy.rejectReason(feeSats = 250L, amountSats = 50_000L, confirmedMaxFeeSats = null))
    }

    @Test
    fun `dropped sub-dust change is added to the fee actually paid`() {
        assertEquals(1_600L, WalletFeePolicy.effectiveFeeSats(computedFeeSats = 1_400L, changeSats = 200L, dustThresholdSats = 546L))
    }

    @Test
    fun `change above dust is not folded into the fee`() {
        assertEquals(1_400L, WalletFeePolicy.effectiveFeeSats(computedFeeSats = 1_400L, changeSats = 5_000L, dustThresholdSats = 546L))
        assertEquals(1_400L, WalletFeePolicy.effectiveFeeSats(computedFeeSats = 1_400L, changeSats = 0L, dustThresholdSats = 546L))
    }
}
