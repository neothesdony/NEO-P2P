package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors the service-level release gate (Task 5): funds may ONLY be released
 * from RECEIPT_SENT/CONFIRMING. FUNDED/SIGNED/PAYMENT_PENDING must never
 * release — the seller's confirmReceipt is the ONLY release gate.
 */
class EscrowReleaseGateTest {

    @Test
    fun `only receipt states allow release`() {
        assertEquals("FUNDED cannot release", false, EscrowService.canReleaseFromStatus("FUNDED"))
        assertEquals("SIGNED cannot release", false, EscrowService.canReleaseFromStatus("SIGNED"))
        assertEquals("PAYMENT_PENDING cannot release", false, EscrowService.canReleaseFromStatus("PAYMENT_PENDING"))
        assertEquals("RECEIPT_SENT can release", true, EscrowService.canReleaseFromStatus("RECEIPT_SENT"))
        assertEquals("CONFIRMING can release", true, EscrowService.canReleaseFromStatus("CONFIRMING"))
    }

    @Test
    fun `terminal states cannot re-release`() {
        assertEquals(false, EscrowService.canReleaseFromStatus("RELEASED"))
        assertEquals(false, EscrowService.canReleaseFromStatus("DISPUTED"))
        assertEquals(false, EscrowService.canReleaseFromStatus("CANCELLED"))
        assertEquals(false, EscrowService.canReleaseFromStatus("REFUNDED"))
    }
}
