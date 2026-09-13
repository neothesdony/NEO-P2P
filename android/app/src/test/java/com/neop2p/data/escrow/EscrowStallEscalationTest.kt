package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-1 (2026-09-13): a stalled FUNDED/SIGNED escrow is escalated to a dispute,
 * never auto-refunded (a refund needs the arbitrator's co-signature).
 */
class EscrowStallEscalationTest {

    private val window = EscrowService.ESCROW_FUNDED_STALL_TIMEOUT_MS
    private val grace = EscrowService.FUNDED_STALL_GRACE_MS

    @Test
    fun `inside the window nothing happens`() {
        assertEquals("NONE", EscrowService.stalledFundedAction(window / 2))
        assertEquals("NONE", EscrowService.stalledFundedAction(window))
    }

    @Test
    fun `past the window reminds`() {
        assertEquals("REMIND", EscrowService.stalledFundedAction(window + 1))
        assertEquals("REMIND", EscrowService.stalledFundedAction(window + grace))
    }

    @Test
    fun `past window plus grace escalates`() {
        assertEquals("ESCALATE", EscrowService.stalledFundedAction(window + grace + 1))
    }

    @Test
    fun `stall window is two hours with a two hour grace`() {
        assertEquals(2L * 60L * 60L * 1000L, window)
        assertEquals(2L * 60L * 60L * 1000L, grace)
    }
}
