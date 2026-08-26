package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EscrowFundingBindingTest {

    private val outputs = listOf(
        ChainMonitor.TxOutput("tb1qchange", 5000L, 0),
        ChainMonitor.TxOutput("tb1qescrow", 123000L, 1)
    )

    @Test
    fun `finds the vout paying escrow address with exact amount`() {
        assertEquals(1, EscrowService.findFundingOutput(outputs, "tb1qescrow", 123000L))
    }

    @Test
    fun `null when address mismatch`() {
        assertNull(EscrowService.findFundingOutput(outputs, "tb1qother", 123000L))
    }

    @Test
    fun `null when amount mismatch`() {
        assertNull(EscrowService.findFundingOutput(outputs, "tb1qescrow", 999L))
    }

    @Test
    fun `null on empty outputs`() {
        assertNull(EscrowService.findFundingOutput(emptyList(), "tb1qescrow", 123000L))
    }

    @Test
    fun `null when address is null`() {
        assertNull(EscrowService.findFundingOutput(outputs, null, 123000L))
    }
}
