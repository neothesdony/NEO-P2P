package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-09-26 recovery fix: building a dispute payload for an ALREADY-DISPUTED
 * escrow must not downgrade it to SIGNED. `generatePayoutTransaction` used to
 * write SIGNED unconditionally, so a recovery re-send whose counterparty
 * delivery failed early-returned and left the row showing a live SIGNED trade,
 * with the two devices disagreeing.
 */
class EscrowPayoutStatusTest {

    @Test
    fun `a dispute is never downgraded to signed`() {
        assertEquals("DISPUTED", EscrowService.payoutResultStatus("DISPUTED"))
        assertEquals("RESOLVING", EscrowService.payoutResultStatus("RESOLVING"))
        assertEquals("RELEASED", EscrowService.payoutResultStatus("RELEASED"))
        assertEquals("REFUNDED", EscrowService.payoutResultStatus("REFUNDED"))
        assertEquals("CANCELLED", EscrowService.payoutResultStatus("CANCELLED"))
    }

    @Test
    fun `pre-payout forward states advance to signed`() {
        assertEquals("SIGNED", EscrowService.payoutResultStatus("FUNDED"))
        assertEquals("SIGNED", EscrowService.payoutResultStatus("PAYMENT_PENDING"))
        assertEquals("SIGNED", EscrowService.payoutResultStatus("RECEIPT_SENT"))
        assertEquals("SIGNED", EscrowService.payoutResultStatus("CONFIRMING"))
        assertEquals("SIGNED", EscrowService.payoutResultStatus("SIGNED"))
    }
}
