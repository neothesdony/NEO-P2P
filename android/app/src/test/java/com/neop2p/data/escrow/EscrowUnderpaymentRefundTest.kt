package com.neop2p.data.escrow

import com.neop2p.domain.model.EscrowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-14 (2026-09-15): a partial on-chain deposit must never be locally
 * cancelled — it is either awaiting confirmation or escalated to a dispute the
 * arbitrator co-signs. Composes [EscrowService.cancelRequiresOnChainRefund]
 * with [EscrowService.refundRequestKind] exactly as the "Request refund" path
 * does.
 *
 * NOTE: current code returns WAIT_FOR_CONFIRMATION (not OPEN_DISPUTE) for a
 * FUNDING escrow with an on-chain deposit, because the FUNDING branch precedes
 * the dispute branch. That is the locked behaviour (also pinned by
 * EscrowRefundRoutingTest); the deposit routes to dispute once funded.
 */
class EscrowUnderpaymentRefundTest {

    private val txid = "1111111111111111111111111111111111111111111111111111111111111111"

    private fun requestRefund(status: String, fundingTxId: String?, fundedAmountSats: Long?): EscrowService.RefundRequestKind {
        val parsed = EscrowStatus.valueOf(status)
        val requiresOnChainRefund = EscrowService.cancelRequiresOnChainRefund(parsed, fundingTxId, fundedAmountSats)
        return EscrowService.refundRequestKind(status, requiresOnChainRefund, EscrowService.canDisputeFromStatus(status))
    }

    @Test fun `partial deposit never cancels locally`() {
        val kind = requestRefund("FUNDING", txid, 50_000L)
        assertTrue(kind != EscrowService.RefundRequestKind.LOCAL_CANCEL)
        assertEquals(EscrowService.RefundRequestKind.WAIT_FOR_CONFIRMATION, kind)
    }

    @Test fun `unfunded funding may still cancel locally`() {
        assertEquals(EscrowService.RefundRequestKind.LOCAL_CANCEL, requestRefund("FUNDING", null, null))
        assertEquals(EscrowService.RefundRequestKind.LOCAL_CANCEL, requestRefund("FUNDING", "", 0L))
    }

    @Test fun `funding is not disputable while unconfirmed`() {
        assertFalse(EscrowService.canDisputeFromStatus("FUNDING"))
    }

    @Test fun `a confirmed partial deposit routes to a dispute`() {
        assertTrue(EscrowService.canDisputeFromStatus("FUNDED"))
        assertEquals(EscrowService.RefundRequestKind.OPEN_DISPUTE, requestRefund("FUNDED", txid, 50_000L))
    }

    @Test fun `already disputed and terminal states reject`() {
        assertEquals(EscrowService.RefundRequestKind.REJECT, requestRefund("DISPUTED", txid, 50_000L))
        for (status in listOf("RELEASED", "REFUNDED", "CANCELLED")) {
            assertEquals(
                "status $status must reject",
                EscrowService.RefundRequestKind.REJECT,
                requestRefund(status, txid, 50_000L)
            )
        }
    }
}
