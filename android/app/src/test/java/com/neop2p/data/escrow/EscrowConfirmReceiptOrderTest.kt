package com.neop2p.data.escrow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-02 (2026-09-15): the seller's fiat confirmation is the ONLY release gate,
 * and the release path is ordered status -> buyer signature -> pre-broadcast
 * integrity. A SIGNED (or FUNDED/PAYMENT_PENDING) escrow with no stored buyer
 * signature must never be treated as release-ready, and a releasable status
 * still requires the buyer signature + [ReleaseIntegrity] verdict.
 *
 * Pure companion seam — no DB, no service instance.
 */
class EscrowConfirmReceiptOrderTest {

    private val buyerKey = "02aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"

    @Test fun `signed is a forward state but never release-ready`() {
        assertTrue(EscrowService.canReleaseFromStatus("SIGNED").not())
        assertFalse(EscrowService.releaseReadiness("SIGNED", hasBuyerSig = false, gateOk = true))
        assertFalse(EscrowService.releaseReadiness("SIGNED", hasBuyerSig = true, gateOk = true))
    }

    @Test fun `pre-payment states are not release-ready`() {
        for (status in listOf("FUNDING", "FUNDED", "PAYMENT_PENDING")) {
            assertFalse(EscrowService.releaseReadiness(status, hasBuyerSig = true, gateOk = true))
        }
    }

    @Test fun `receipt states require the buyer signature`() {
        assertFalse(EscrowService.releaseReadiness("RECEIPT_SENT", hasBuyerSig = false, gateOk = true))
        assertFalse(EscrowService.releaseReadiness("CONFIRMING", hasBuyerSig = false, gateOk = true))
    }

    @Test fun `releasable status still fails closed without the integrity gate`() {
        assertFalse(EscrowService.releaseReadiness("RECEIPT_SENT", hasBuyerSig = true, gateOk = false))
        assertFalse(EscrowService.releaseReadiness("CONFIRMING", hasBuyerSig = true, gateOk = false))
    }

    @Test fun `receipt plus buyer signature plus gate is release-ready`() {
        assertTrue(EscrowService.releaseReadiness("RECEIPT_SENT", hasBuyerSig = true, gateOk = true))
        assertTrue(EscrowService.releaseReadiness("CONFIRMING", hasBuyerSig = true, gateOk = true))
    }

    @Test fun `no buyer signature is never accepted`() {
        assertFalse(EscrowService.isValidBuyerSignature("", buyerKey))
        assertFalse(EscrowService.isValidBuyerSignature("   ", buyerKey))
    }
}
