package com.neop2p.data.escrow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-26: [EscrowService.confirmReceipt] moves the escrow to CONFIRMING and
 * then runs [EscrowService.releaseWhenReady] best-effort. With no buyer
 * signature yet — the NORMAL state moments after the seller confirms — the
 * benign "Awaiting the buyer's payout signature" failure must NOT be surfaced
 * to the UI. Previously it was, so the seller saw
 * "Release failed: Awaiting the buyer's payout signature (C1d)" while the
 * payout still broadcast seconds later when the signature arrived.
 *
 * Pure companion seam — no DB, no service instance (matches the repo's
 * EscrowConfirmReceiptOrderTest / EscrowReleaseGateTest style).
 */
class EscrowConfirmReceiptResultTest {

    @Test
    fun `awaiting buyer signature is never surfaced as a release failure`() {
        assertFalse(
            "no buyer signature must yield a successful CONFIRMING result",
            EscrowService.confirmReceiptSurfacesRelease(hadBuyerSignature = false)
        )
    }

    @Test
    fun `a release actually attempted with the buyer signature is surfaced`() {
        assertTrue(
            "with a stored signature the release outcome is real and must be reported",
            EscrowService.confirmReceiptSurfacesRelease(hadBuyerSignature = true)
        )
    }

    @Test
    fun `no buyer signature means the escrow is not yet release-ready`() {
        // Documents why the missing-signature branch is the normal state: the
        // release gate intentionally refuses until the signature exists.
        assertFalse(EscrowService.releaseReadiness("CONFIRMING", hasBuyerSig = false))
    }
}
