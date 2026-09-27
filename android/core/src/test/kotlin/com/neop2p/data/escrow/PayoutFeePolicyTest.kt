package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayoutFeePolicyTest {

    @Test fun `an exact deposit reuses the stored fee and leaves no excess`() {
        // input = trade 100000 + fee 500 + network 1000
        val p = PayoutFeePolicy.plan(
            storedFeeSats = 1000, liveFeeSats = 1000, inputValueSats = 101_500,
            tradeSats = 100_000, feeAmountSats = 500, feeOutputPresent = true
        )!!
        assertEquals(1000L, p.minerFeeSats); assertEquals(0L, p.sellerExcessSats); assertTrue(!p.bumped)
    }

    @Test fun `an overpayment absorbs a live fee bump`() {
        // input = trade 100000 + fee 500 + network 1000 + overpay 5000
        val p = PayoutFeePolicy.plan(
            storedFeeSats = 1000, liveFeeSats = 4000, inputValueSats = 106_500,
            tradeSats = 100_000, feeAmountSats = 500, feeOutputPresent = true
        )!!
        assertEquals(4000L, p.minerFeeSats); assertEquals(2000L, p.sellerExcessSats); assertTrue(p.bumped)
    }

    @Test fun `a live fee below the stored fee never lowers the miner fee`() {
        val p = PayoutFeePolicy.plan(
            storedFeeSats = 1000, liveFeeSats = 100, inputValueSats = 106_500,
            tradeSats = 100_000, feeAmountSats = 500, feeOutputPresent = true
        )!!
        assertEquals(1000L, p.minerFeeSats)
    }

    @Test fun `a dropped sub-dust fee output widens the slack`() {
        // fee output absent -> feeAmount is not paid out, so it can fund the miner fee
        val p = PayoutFeePolicy.plan(
            storedFeeSats = 1000, liveFeeSats = 1000, inputValueSats = 101_000,
            tradeSats = 100_000, feeAmountSats = 500, feeOutputPresent = false
        )!!
        assertEquals(1000L, p.minerFeeSats); assertEquals(0L, p.sellerExcessSats)
    }

    @Test fun `no slack to cover the minimum fee fails closed`() {
        assertNull(
            PayoutFeePolicy.plan(
                storedFeeSats = 1000, liveFeeSats = 1000, inputValueSats = 100_000,
                tradeSats = 100_000, feeAmountSats = 500, feeOutputPresent = true
            )
        )
    }

    @Test fun `the RBF sequence is non-final`() {
        assertTrue(PayoutFeePolicy.RBF_SEQUENCE <= 0xfffffffdL)
    }
}
