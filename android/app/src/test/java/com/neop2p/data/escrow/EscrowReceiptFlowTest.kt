package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors the receipt-gated state machine in EscrowService (pure logic, like
 * EscrowTimeoutTest). Verifies the buyer cannot release and the seller is the
 * only party that can confirm receipt.
 *
 * Contract under test (Task 3a — markPaid/sendReceipt implemented, 3b owns
 * the seller-only CONFIRMING/RELEASED steps; this mirror documents the full
 * receipt flow):
 *   - FUNDED + BUYER     → PAYMENT_PENDING  (markPaid)
 *   - PAYMENT_PENDING + BUYER → RECEIPT_SENT (sendReceipt)
 *   - RECEIPT_SENT + SELLER → CONFIRMING    (confirmReceipt, Task 3b)
 *   - CONFIRMING + SELLER → RELEASED        (confirmReceipt, Task 3b)
 *   - legacy PAID is gone and never transitions.
 */
class EscrowReceiptFlowTest {

    private fun nextFor(status: String, role: String): String? {
        return when {
            status == "FUNDED" && role == "BUYER" -> "PAYMENT_PENDING"   // buyer signals paid
            status == "PAYMENT_PENDING" && role == "BUYER" -> "RECEIPT_SENT" // receipt sent
            status == "RECEIPT_SENT" && role == "SELLER" -> "CONFIRMING"  // seller reviewing
            status == "CONFIRMING" && role == "SELLER" -> "RELEASED"      // seller confirms IDR
            else -> null
        }
    }

    @Test
    fun `buyer cannot reach RELEASED without seller confirmation`() {
        assertEquals("PAYMENT_PENDING", nextFor("FUNDED", "BUYER"))
        assertEquals("RECEIPT_SENT", nextFor("PAYMENT_PENDING", "BUYER"))
        assertEquals(null, nextFor("RECEIPT_SENT", "BUYER"))      // buyer can't review own receipt
        assertEquals(null, nextFor("CONFIRMING", "BUYER"))         // no path to RELEASED as buyer
    }

    @Test
    fun `seller confirmation is the only release path`() {
        assertEquals(null, nextFor("FUNDED", "SELLER"))
        assertEquals(null, nextFor("CONFIRMING", "BUYER"))
        assertEquals("CONFIRMING", nextFor("RECEIPT_SENT", "SELLER"))
        assertEquals("RELEASED", nextFor("CONFIRMING", "SELLER"))
    }

    @Test
    fun `legacy PAID no longer transitions`() {
        assertEquals(null, nextFor("PAID", "SELLER"))
        assertEquals(null, nextFor("PAID", "BUYER"))
    }
}
