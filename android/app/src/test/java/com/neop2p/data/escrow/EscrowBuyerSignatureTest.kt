package com.neop2p.data.escrow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1d (2026-09-11): the buyer's payout signature must be accepted ONLY when
 * it verifies against the escrow's buyer_pubkey_hex. This is the exact gate
 * storeBuyerSignature applies before persisting a remote buyer signature —
 * a forged/wrong-key signature must never be stored (it would poison the
 * release and strand funds). Pure so it is testable without a DB.
 */
class EscrowBuyerSignatureTest {

    private val buyerKey = "02aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
    private val sellerKey = "02ffeeddccbbaa00998877665544332211ffeeddccbbaa00998877665544332211"

    @Test
    fun `blank buyer signature is rejected`() {
        assertFalse(EscrowService.isValidBuyerSignature("", buyerKey))
        assertFalse(EscrowService.isValidBuyerSignature("   ", buyerKey))
    }

    @Test
    fun `blank buyer pubkey is rejected`() {
        assertFalse(EscrowService.isValidBuyerSignature("deadbeef", ""))
    }

    @Test
    fun `non-hex signature is rejected`() {
        assertFalse(EscrowService.isValidBuyerSignature("not-hex!", buyerKey))
    }

    @Test
    fun `a signature that is not a valid DER+SIGHASH is rejected`() {
        // 71 bytes of 0x00 is not a valid DER signature.
        assertFalse(EscrowService.isValidBuyerSignature("00".repeat(71), buyerKey))
    }
}
