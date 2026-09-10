package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Overpayment handling (2026-09-04): a seller who deposits MORE than the
 * exact `depositAmountSats` must not strand the excess in the 2-of-3
 * multisig. The funding output is accepted when it pays the escrow address
 * AT LEAST the deposit; the ACTUAL on-chain value is recorded so the payout
 * and refund spend the real input value (SegWit BIP-143 commits it) and
 * return the excess to the seller.
 */
class EscrowOverpaymentTest {

    private val outputs = listOf(
        ChainMonitor.TxOutput("tb1qchange", 5000L, 0),
        ChainMonitor.TxOutput("tb1qescrow", 123_000L, 1)
    )

    @Test
    fun `exact deposit still matches`() {
        assertEquals(1, EscrowService.findFundingOutputAtLeast(outputs, "tb1qescrow", 123_000L))
    }

    @Test
    fun `overpayment matches the funding output`() {
        assertEquals(1, EscrowService.findFundingOutputAtLeast(outputs, "tb1qescrow", 100_000L))
    }

    @Test
    fun `null when address mismatch`() {
        assertNull(EscrowService.findFundingOutputAtLeast(outputs, "tb1qother", 100_000L))
    }

    @Test
    fun `null when underpaid`() {
        assertNull(EscrowService.findFundingOutputAtLeast(outputs, "tb1qescrow", 200_000L))
    }

    @Test
    fun `null on empty outputs`() {
        assertNull(EscrowService.findFundingOutputAtLeast(emptyList(), "tb1qescrow", 100_000L))
    }

    @Test
    fun `null when address is null`() {
        assertNull(EscrowService.findFundingOutputAtLeast(outputs, null, 100_000L))
    }

    @Test
    fun `funded value is the actual on-chain output value`() {
        assertEquals(123_000L, EscrowService.fundedValueSats(outputs, "tb1qescrow", 100_000L))
    }

    @Test
    fun `funded value equals deposit when exact`() {
        assertEquals(123_000L, EscrowService.fundedValueSats(outputs, "tb1qescrow", 123_000L))
    }

    @Test
    fun `funded value null when no qualifying output`() {
        assertNull(EscrowService.fundedValueSats(outputs, "tb1qother", 100_000L))
    }
}
