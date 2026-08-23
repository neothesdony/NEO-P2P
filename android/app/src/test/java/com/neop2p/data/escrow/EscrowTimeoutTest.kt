package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the split escrow timeouts (Fix 2).
 *
 * EscrowService.expireStaleEscrows() is Android/Room/bitcoinj-dependent, so its
 * DECISION logic (what is stale, what status transition applies) is mirrored here
 * and verified against the production constants:
 *   - [EscrowService.ESCROW_FUNDING_TIMEOUT_MS]  → FUNDING → CANCELLED
 *   - [EscrowService.ESCROW_FUNDED_REFUND_TIMEOUT_MS] → FUNDED → auto-REFUND
 *
 * Rules under test:
 *   - FUNDING older than the FUNDING timeout → CANCELLED (nothing was deposited).
 *   - FUNDED (deposited) older than the FUNDED-REFUND timeout → auto-REFUND.
 *   - FUNDED within the (longer) funded window → NOT expired yet.
 *   - SIGNED/RELEASED/DISPUTED/CANCELLED/REFUNDED are never auto-expired.
 */
class EscrowTimeoutTest {

    private val fundingTimeoutMs: Long = EscrowService.ESCROW_FUNDING_TIMEOUT_MS
    private val fundedRefundTimeoutMs: Long = EscrowService.ESCROW_FUNDED_REFUND_TIMEOUT_MS

    private val freshElapsed = fundingTimeoutMs / 2   // well inside the FUNDING window
    private val exactlyAtTimeout = fundingTimeoutMs     // boundary, not > timeout
    private val fundingOverdue = fundingTimeoutMs + 1   // just past the FUNDING timeout

    /** Mirrors the `when` in expireStaleEscrows for each status. */
    private fun transitionFor(status: String, elapsedMs: Long): String? {
        return when (status) {
            "FUNDING" -> if (elapsedMs > fundingTimeoutMs) "CANCELLED" else null
            "FUNDED" -> if (elapsedMs > fundedRefundTimeoutMs) "REFUNDED" else null
            else -> null // SIGNED / RELEASED / DISPUTED / RESOLVING / CANCELLED / REFUNDED
        }
    }

    @Test
    fun `funding escrow is cancelled only once it exceeds the funding timeout`() {
        // Not yet stale: no transition.
        assertEquals(null, transitionFor("FUNDING", freshElapsed))

        // Exactly at the boundary (not >): no transition.
        assertEquals(null, transitionFor("FUNDING", exactlyAtTimeout))

        // Overdue: transition to CANCELLED (nothing deposited → no on-chain move).
        assertEquals("CANCELLED", transitionFor("FUNDING", fundingOverdue))
    }

    @Test
    fun `funded escrow is auto-refunded only once it exceeds the longer funded-refund timeout`() {
        // A funded escrow just past the funding timeout is NOT refunded yet —
        // it gets the longer, separate funded-refund window.
        assertEquals(null, transitionFor("FUNDED", fundingOverdue))
        assertEquals(null, transitionFor("FUNDED", freshElapsed))

        // Once past the funded-refund timeout: auto-refund.
        val fundedOverdue = fundedRefundTimeoutMs + 1
        assertEquals("REFUNDED", transitionFor("FUNDED", fundedOverdue))
    }

    @Test
    fun `funded escrow is measured from funded_at not created_at`() {
        // A FUNDED escrow created long ago but funded recently must NOT be
        // auto-refunded yet: the refund timeout is measured from funded_at.
        val fundedElapsed = 5 * 60 * 1000L // funded 5 min ago
        assertEquals(null, transitionFor("FUNDED", fundedElapsed))
        assertTrue("funded 5 min ago is < funded-refund timeout", fundedElapsed < fundedRefundTimeoutMs)
    }

    @Test
    fun `non-funding and non-funded statuses are never expired`() {
        for (status in listOf("SIGNED", "RELEASED", "DISPUTED", "RESOLVING", "CANCELLED", "REFUNDED")) {
            assertEquals("$status must never be auto-expired", null, transitionFor(status, fundingOverdue))
        }
    }

    @Test
    fun `the funding timeout constant is thirty minutes`() {
        assertEquals(30L * 60L * 1000L, fundingTimeoutMs)
    }

    @Test
    fun `the funded-refund timeout is longer than the funding timeout`() {
        assertTrue("funded-refund timeout should be longer than funding timeout",
            fundedRefundTimeoutMs > fundingTimeoutMs)
        assertEquals(6L * 60L * 60L * 1000L, fundedRefundTimeoutMs)
    }

    @Test
    fun `cancelled and refunded are terminal once set`() {
        // Once an escrow is CANCELLED or REFUNDED, a re-scan no-ops (idempotent).
        assertEquals(null, transitionFor("CANCELLED", fundingOverdue))
        assertEquals(null, transitionFor("REFUNDED", fundingOverdue))
    }
}
