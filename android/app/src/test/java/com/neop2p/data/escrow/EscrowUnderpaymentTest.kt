package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Underpayment handling (2026-09-04): a seller who deposits LESS than the
 * required `depositAmountSats` must not have their partial BTC stranded in
 * the 2-of-3 multisig. The partial deposit is persisted (txid/vout/value) so
 * the seller can Cancel & Refund it; the escrow is never auto-cancelled while
 * a partial deposit sits on-chain.
 */
class EscrowUnderpaymentTest {

    private val outputs = listOf(
        ChainMonitor.TxOutput("tb1qchange", 5000L, 0),
        ChainMonitor.TxOutput("tb1qescrow", 50_000L, 1)
    )

    @Test
    fun `finds any output paying the escrow address`() {
        assertEquals(1, EscrowService.findFundingOutputAny(outputs, "tb1qescrow"))
    }

    @Test
    fun `null when no output pays the address`() {
        assertNull(EscrowService.findFundingOutputAny(outputs, "tb1qother"))
    }

    @Test
    fun `null on empty outputs`() {
        assertNull(EscrowService.findFundingOutputAny(emptyList(), "tb1qescrow"))
    }

    @Test
    fun `null when address is null`() {
        assertNull(EscrowService.findFundingOutputAny(outputs, null))
    }

    @Test
    fun `partial value is the actual output value`() {
        assertEquals(50_000L, EscrowService.fundedValueAny(outputs, "tb1qescrow"))
    }

    @Test
    fun `partial value null when no qualifying output`() {
        assertNull(EscrowService.fundedValueAny(outputs, "tb1qother"))
    }
}
