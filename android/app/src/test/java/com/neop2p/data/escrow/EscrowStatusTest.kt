package com.neop2p.data.escrow

import com.neop2p.domain.model.EscrowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the status enum consumed by the UI (progress map + step tracker).
 * Guards the status vocabulary the rest of the flow depends on.
 */
class EscrowStatusTest {

    @Test
    fun `status enum contains the new guided-flow states`() {
        val names = EscrowStatus.entries.map { it.name }
        assertTrue("PAYMENT_PENDING missing", names.contains("PAYMENT_PENDING"))
        assertTrue("RECEIPT_SENT missing", names.contains("RECEIPT_SENT"))
        assertTrue("CONFIRMING missing", names.contains("CONFIRMING"))
        assertTrue("legacy PAID must be gone", !names.contains("PAID"))
    }

    @Test
    fun `terminal and fallback statuses are preserved`() {
        val names = EscrowStatus.entries.map { it.name }
        for (expected in listOf("FUNDING", "FUNDED", "RELEASED", "DISPUTED", "CANCELLED", "REFUNDED")) {
            assertTrue("$expected missing", names.contains(expected))
        }
    }
}
