package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-1 (2026-09-13): what "Request refund" means in a given escrow state.
 * A refund is a 2-of-3 spend and the seller's key fills only one slot, so the
 * only routes are a local cancel (nothing on-chain) or a dispute the
 * arbitrator co-signs.
 */
class EscrowRefundRoutingTest {

    @Test
    fun `funding with no deposit cancels locally`() {
        assertEquals(
            EscrowService.RefundRequestKind.LOCAL_CANCEL,
            EscrowService.refundRequestKind("FUNDING", requiresOnChainRefund = false, canDispute = false)
        )
    }

    @Test
    fun `funding with a txid waits for confirmation`() {
        assertEquals(
            EscrowService.RefundRequestKind.WAIT_FOR_CONFIRMATION,
            EscrowService.refundRequestKind("FUNDING", requiresOnChainRefund = true, canDispute = false)
        )
    }

    @Test
    fun `funding with a partial deposit waits for confirmation`() {
        assertEquals(
            EscrowService.RefundRequestKind.WAIT_FOR_CONFIRMATION,
            EscrowService.refundRequestKind("FUNDING", requiresOnChainRefund = true, canDispute = true)
        )
    }

    @Test
    fun `funded opens a dispute`() {
        assertEquals(
            EscrowService.RefundRequestKind.OPEN_DISPUTE,
            EscrowService.refundRequestKind("FUNDED", requiresOnChainRefund = true, canDispute = true)
        )
    }

    @Test
    fun `in-flight payment states open a dispute`() {
        for (status in listOf("SIGNED", "PAYMENT_PENDING", "RECEIPT_SENT", "CONFIRMING")) {
            assertEquals(
                "status $status must open a dispute",
                EscrowService.RefundRequestKind.OPEN_DISPUTE,
                EscrowService.refundRequestKind(status, requiresOnChainRefund = true, canDispute = true)
            )
        }
    }

    @Test
    fun `already disputed and terminal states reject`() {
        assertEquals(
            EscrowService.RefundRequestKind.REJECT,
            EscrowService.refundRequestKind("DISPUTED", requiresOnChainRefund = true, canDispute = false)
        )
        for (status in listOf("RELEASED", "REFUNDED", "CANCELLED")) {
            assertEquals(
                "status $status must reject",
                EscrowService.RefundRequestKind.REJECT,
                EscrowService.refundRequestKind(status, requiresOnChainRefund = true, canDispute = false)
            )
        }
    }

    @Test
    fun `unknown status rejects`() {
        assertEquals(
            EscrowService.RefundRequestKind.REJECT,
            EscrowService.refundRequestKind("BOGUS", requiresOnChainRefund = false, canDispute = true)
        )
    }
}
