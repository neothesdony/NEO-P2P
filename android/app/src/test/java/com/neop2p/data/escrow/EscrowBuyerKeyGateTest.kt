package com.neop2p.data.escrow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1 (2026-09-11): the 2-of-3 must use the REAL buyer key. The old
 * single-key model (buyerPubKeyHex == sellerPubKeyHex on one device) made
 * the multisig effectively 2-of-2 — the seller could sign a refund to
 * themselves after receiving fiat. The gate is pure so it is testable
 * without a DB.
 */
class EscrowBuyerKeyGateTest {

    private val sellerKey = "02aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
    private val buyerKey = "02ffeeddccbbaa00998877665544332211ffeeddccbbaa00998877665544332211"

    @Test
    fun `distinct buyer and seller keys pass the gate`() {
        assertTrue(EscrowService.isValidRoleKeyPair(buyerKey, sellerKey))
    }

    @Test
    fun `identical buyer and seller keys fail the gate`() {
        assertFalse(EscrowService.isValidRoleKeyPair(sellerKey, sellerKey))
    }

    @Test
    fun `blank buyer key fails the gate`() {
        assertFalse(EscrowService.isValidRoleKeyPair("", sellerKey))
        assertFalse(EscrowService.isValidRoleKeyPair("   ", sellerKey))
    }

    @Test
    fun `blank seller key fails the gate`() {
        assertFalse(EscrowService.isValidRoleKeyPair(buyerKey, ""))
    }
}
