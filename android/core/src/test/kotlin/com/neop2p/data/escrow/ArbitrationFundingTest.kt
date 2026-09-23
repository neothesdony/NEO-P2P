package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

class ArbitrationFundingTest {

    @Test
    fun `ceiling is one percent of the on-chain value`() {
        assertEquals(10_000L, ArbitrationFunding.feeCeiling(1_000_000L))
    }

    @Test
    fun `ceiling has a 5000 sat floor for small deposits`() {
        assertEquals(5_000L, ArbitrationFunding.feeCeiling(100_000L))
        assertEquals(5_000L, ArbitrationFunding.feeCeiling(0L))
    }

    @Test
    fun `expectation uses the on-chain value not the claim`() {
        val e = ArbitrationFunding.refundExpectation("bc1qexample", 2_000_000L)
        assertEquals("bc1qexample", e.destinationAddress)
        assertEquals(2_000_000L, e.fundedInputSats)
        assertEquals(20_000L, e.feeCeilingSats)
    }
}
