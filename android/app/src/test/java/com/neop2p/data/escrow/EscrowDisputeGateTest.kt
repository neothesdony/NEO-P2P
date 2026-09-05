package com.neop2p.data.escrow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dispute gate (2026-09-05): a dispute may only be opened once the escrow is
 * FUNDED (deposit confirmed on-chain). FUNDING is NOT disputable — the deposit
 * is either not yet broadcast (nothing to arbitrate; the 45-min funding window
 * auto-cancels) or in flight (unconfirmed; the arbitrator's payout/refund
 * would spend an output that does not exist yet and fail to broadcast).
 *
 * Mirrors the guard in EscrowService.disputeEscrow, the FUNDING buyer view in
 * EscrowScreen, P2POrchestrator.isDisputableStatus, and
 * EscrowRouter.applyRemoteStatus — the rule is verified here against the
 * production function.
 */
class EscrowDisputeGateTest {

    @Test
    fun `funding is never disputable`() {
        assertFalse(EscrowService.canDisputeFromStatus("FUNDING"))
    }

    @Test
    fun `funded and later states are disputable`() {
        assertTrue(EscrowService.canDisputeFromStatus("FUNDED"))
        assertTrue(EscrowService.canDisputeFromStatus("SIGNED"))
        assertTrue(EscrowService.canDisputeFromStatus("PAYMENT_PENDING"))
        assertTrue(EscrowService.canDisputeFromStatus("RECEIPT_SENT"))
        assertTrue(EscrowService.canDisputeFromStatus("CONFIRMING"))
    }

    @Test
    fun `terminal states are not disputable`() {
        assertFalse(EscrowService.canDisputeFromStatus("RELEASED"))
        assertFalse(EscrowService.canDisputeFromStatus("REFUNDED"))
        assertFalse(EscrowService.canDisputeFromStatus("CANCELLED"))
        assertFalse(EscrowService.canDisputeFromStatus("DISPUTED"))
        assertFalse(EscrowService.canDisputeFromStatus("RESOLVING"))
    }
}
