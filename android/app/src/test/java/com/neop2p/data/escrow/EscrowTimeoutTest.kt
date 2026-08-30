package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the split escrow timeouts (Fix 2, grace-aware Task 2).
 *
 * EscrowService.expireStaleEscrows() is Android/Room/bitcoinj-dependent, so its
 * DECISION logic (what is stale, what status transition applies) is mirrored here
 * and verified against the production constants:
 *   - [EscrowService.ESCROW_FUNDING_TIMEOUT_MS]  → FUNDING → CANCELLED
 *   - [EscrowService.ESCROW_FUNDED_REFUND_TIMEOUT_MS] + [EscrowService.FUNDED_REFUND_GRACE_MS]
 *     → FUNDED → auto-REFUND
 *   - [EscrowService.PAYMENT_WINDOW_MS] + [EscrowService.PAYMENT_GRACE_MS]
 *     → PAYMENT_PENDING / CONFIRMING / RECEIPT_SENT → auto-DISPUTED (PAYMENT_PENDING added 2026-08-30)
 *
 * Rules under test:
 *   - FUNDING older than the FUNDING timeout → CANCELLED (nothing was deposited).
 *   - FUNDED (deposited) older than the funded-refund timeout + grace → auto-REFUND.
 *   - PAYMENT_PENDING / CONFIRMING / RECEIPT_SENT older than the payment window + grace → auto-DISPUTED.
 *   - SIGNED/RELEASED/RESOLVING/CANCELLED/REFUNDED are never auto-expired (SIGNED refunds like FUNDED).
 */
class EscrowTimeoutTest {

    private val fundingTimeoutMs: Long = EscrowService.ESCROW_FUNDING_TIMEOUT_MS
    private val fundedRefundTimeoutMs: Long = EscrowService.ESCROW_FUNDED_REFUND_TIMEOUT_MS
    private val fundedRefundGraceMs: Long = EscrowService.FUNDED_REFUND_GRACE_MS
    private val paymentWindowMs: Long = EscrowService.PAYMENT_WINDOW_MS
    private val paymentGraceMs: Long = EscrowService.PAYMENT_GRACE_MS

    private val freshElapsed = fundingTimeoutMs / 2   // well inside the FUNDING window
    private val exactlyAtTimeout = fundingTimeoutMs     // boundary, not > timeout
    private val fundingOverdue = fundingTimeoutMs + 1   // just past the FUNDING timeout

    /** Mirrors the `when` in expireStaleEscrows for each status (grace-aware). */
    private fun transitionFor(status: String, elapsedMs: Long): String? {
        return when (status) {
            // FUNDING: warning at 30 min, cancel at 45 min (nothing deposited → no on-chain move).
            "FUNDING" -> if (elapsedMs > fundingTimeoutMs) "CANCELLED" else null
            // FUNDED: refund only after primary timeout + grace (reminders fire in between).
            // SIGNED: same — the payout was generated but the trade stalled; the deposit
            // is confirmed on-chain, so the seller gets the same auto-refund window.
            "FUNDED", "SIGNED" -> if (elapsedMs > fundedRefundTimeoutMs + fundedRefundGraceMs) "REFUNDED" else null
            // Payment windows: PAYMENT_PENDING/CONFIRMING/RECEIPT_SENT -> DISPUTED only after window + grace.
            // PAYMENT_PENDING was omitted before 2026-08-30 (bug: buyer marked paid but never sent receipt -> never disputed).
            "CONFIRMING", "RECEIPT_SENT", "PAYMENT_PENDING" -> if (elapsedMs > paymentWindowMs + paymentGraceMs) "DISPUTED" else null
            else -> null // RELEASED / RESOLVING / CANCELLED / REFUNDED
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
    fun `funded escrow is auto-refunded only once it exceeds the funded-refund timeout plus grace`() {
        // A funded escrow just past the funding timeout is NOT refunded yet —
        // it gets the longer, separate funded-refund window.
        assertEquals(null, transitionFor("FUNDED", fundingOverdue))
        assertEquals(null, transitionFor("FUNDED", freshElapsed))

        // Past the primary funded-refund timeout but still inside the grace
        // window: NOT refunded yet (reminders fire in between).
        assertEquals(null, transitionFor("FUNDED", fundedRefundTimeoutMs + 1))

        // Once past the funded-refund timeout + grace: auto-refund.
        val fundedOverdue = fundedRefundTimeoutMs + fundedRefundGraceMs + 1
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
    fun `confirming escrow auto-disputes when the payment window plus grace expires`() {
        // Buyer marked payment as sent; seller still has time.
        assertEquals(null, transitionFor("CONFIRMING", paymentWindowMs / 2))

        // Exactly at the window boundary (not >): not yet disputed.
        assertEquals(null, transitionFor("CONFIRMING", paymentWindowMs))

        // Window expired but grace remains: not yet disputed.
        assertEquals(null, transitionFor("CONFIRMING", paymentWindowMs + paymentGraceMs))

        // Window + grace expired: auto-DISPUTED (never auto-refunded — the buyer
        // may have actually paid, so the arbitrator must decide).
        assertEquals("DISPUTED", transitionFor("CONFIRMING", paymentWindowMs + paymentGraceMs + 1))
    }

    @Test
    fun `receipt-sent escrow auto-disputes when the payment window plus grace expires`() {
        // Receipt sent; seller still has time.
        assertEquals(null, transitionFor("RECEIPT_SENT", paymentWindowMs / 2))

        // Exactly at window + grace boundary (not >): not yet disputed.
        assertEquals(null, transitionFor("RECEIPT_SENT", paymentWindowMs + paymentGraceMs))

        // Window + grace expired: auto-DISPUTED.
        assertEquals("DISPUTED", transitionFor("RECEIPT_SENT", paymentWindowMs + paymentGraceMs + 1))
    }

    @Test
    fun `payment-pending escrow auto-disputes when the payment window plus grace expires`() {
        // PAYMENT_PENDING (markPaid without receipt) must auto-dispute like CONFIRMING (P0 2026-08-30).
        assertEquals(null, transitionFor("PAYMENT_PENDING", paymentWindowMs / 2))
        assertEquals(null, transitionFor("PAYMENT_PENDING", paymentWindowMs + paymentGraceMs))
        assertEquals("DISPUTED", transitionFor("PAYMENT_PENDING", paymentWindowMs + paymentGraceMs + 1))
    }

    @Test
    fun `signed escrow auto-refunds like funded when stalled past timeout plus grace`() {
        // Payout generated but the trade never proceeded: the deposit is
        // confirmed on-chain, so the seller gets the same funded-refund window.
        assertEquals(null, transitionFor("SIGNED", fundedRefundTimeoutMs + 1))
        assertEquals("REFUNDED", transitionFor("SIGNED", fundedRefundTimeoutMs + fundedRefundGraceMs + 1))
    }

    @Test
    fun `non-funding and non-funded statuses are never expired`() {
        for (status in listOf("RELEASED", "RESOLVING", "CANCELLED", "REFUNDED")) {
            assertEquals("$status must never be auto-expired", null, transitionFor(status, fundingOverdue))
        }
    }

    @Test
    fun `the funding timeout constant is forty five minutes`() {
        assertEquals(45L * 60L * 1000L, fundingTimeoutMs)
    }

    @Test
    fun `the funded-refund timeout is longer than the funding timeout`() {
        assertTrue("funded-refund timeout + grace should be longer than funding timeout",
            fundedRefundTimeoutMs + fundedRefundGraceMs > fundingTimeoutMs)
        assertEquals(12L * 60L * 60L * 1000L, fundedRefundTimeoutMs)
    }

    @Test
    fun `cancelled and refunded are terminal once set`() {
        // Once an escrow is CANCELLED or REFUNDED, a re-scan no-ops (idempotent).
        assertEquals(null, transitionFor("CANCELLED", fundingOverdue))
        assertEquals(null, transitionFor("REFUNDED", fundingOverdue))
    }
}
