package com.neop2p.data.p2p.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mirrors the remote escrow status transition rules in EscrowRouter
 * (Task 8): remote kind:33337 events may advance the happy path but never
 * downgrade a locked/terminal status, and only for escrows the local
 * identity is a party to.
 */
class EscrowRouterApplyTest {

    @Test
    fun `happy path transitions apply`() {
        assertEquals("FUNDED", EscrowRouter.applyRemoteStatus("FUNDING", "FUNDED"))
        assertEquals("PAYMENT_PENDING", EscrowRouter.applyRemoteStatus("FUNDED", "PAYMENT_PENDING"))
        assertEquals("RECEIPT_SENT", EscrowRouter.applyRemoteStatus("PAYMENT_PENDING", "RECEIPT_SENT"))
        assertEquals("CONFIRMING", EscrowRouter.applyRemoteStatus("RECEIPT_SENT", "CONFIRMING"))
        // Release is a happy-path terminal outcome: the seller's broadcast
        // must converge the buyer's mirrored row (regression: RELEASED was
        // missing from ALLOWED_REMOTE + the forward order, so the buyer
        // stayed "Paid — awaiting seller release" forever).
        assertEquals("RELEASED", EscrowRouter.applyRemoteStatus("RECEIPT_SENT", "RELEASED"))
        assertEquals("RELEASED", EscrowRouter.applyRemoteStatus("CONFIRMING", "RELEASED"))
        assertEquals("DISPUTED", EscrowRouter.applyRemoteStatus("CONFIRMING", "DISPUTED"))
    }

    @Test
    fun `signed is a forward state between funded and payment pending`() {
        assertEquals("SIGNED", EscrowRouter.applyRemoteStatus("FUNDED", "SIGNED"))
        assertEquals("PAYMENT_PENDING", EscrowRouter.applyRemoteStatus("SIGNED", "PAYMENT_PENDING"))
        assertEquals("RECEIPT_SENT", EscrowRouter.applyRemoteStatus("SIGNED", "RECEIPT_SENT"))
        assertEquals("CONFIRMING", EscrowRouter.applyRemoteStatus("SIGNED", "CONFIRMING"))
        assertNull(EscrowRouter.applyRemoteStatus("SIGNED", "FUNDED"))
    }

    @Test
    fun `terminal statuses are locked`() {
        assertNull(EscrowRouter.applyRemoteStatus("RELEASED", "FUNDED"))
        assertNull(EscrowRouter.applyRemoteStatus("REFUNDED", "PAYMENT_PENDING"))
        assertNull(EscrowRouter.applyRemoteStatus("CANCELLED", "FUNDING"))
        assertNull(EscrowRouter.applyRemoteStatus("DISPUTED", "CONFIRMING"))
    }

    @Test
    fun `terminal outcomes may land from any non-terminal state`() {
        // The seller's sweep is the authority: auto-cancel / auto-refund /
        // dispute-resolution must converge the buyer's mirrored row even
        // when the buyer is still on an earlier state.
        assertEquals("CANCELLED", EscrowRouter.applyRemoteStatus("FUNDING", "CANCELLED"))
        assertEquals("CANCELLED", EscrowRouter.applyRemoteStatus("FUNDED", "CANCELLED"))
        assertEquals("REFUNDED", EscrowRouter.applyRemoteStatus("FUNDED", "REFUNDED"))
        assertEquals("REFUNDED", EscrowRouter.applyRemoteStatus("RECEIPT_SENT", "REFUNDED"))
        assertEquals("DISPUTED", EscrowRouter.applyRemoteStatus("FUNDING", "DISPUTED"))
    }

    @Test
    fun `never downgrades or jumps backwards`() {
        assertNull(EscrowRouter.applyRemoteStatus("RECEIPT_SENT", "PAYMENT_PENDING"))
        assertNull(EscrowRouter.applyRemoteStatus("FUNDED", "FUNDING"))
        assertNull(EscrowRouter.applyRemoteStatus("CONFIRMING", "FUNDED"))
    }

    @Test
    fun `unknown or same status is a no-op`() {
        assertNull(EscrowRouter.applyRemoteStatus("FUNDED", "FUNDED"))
        assertNull(EscrowRouter.applyRemoteStatus("FUNDED", "BOGUS"))
    }
}
