package com.neop2p.data.escrow

import com.neop2p.NeoP2PConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-15 (2026-09-15): the accept-time and build-time payout destination gate.
 * A payout must never pay the fee wallet or the escrow's own multisig, and the
 * resolver must never fall back to the funding address — the 2026-09-07 bug
 * that paid a buyer's sats back into the escrow.
 */
class PayoutAddressGateAcceptIntegrationTest {

    private val feeWallet = NeoP2PConfig.FEE_WALLET_ADDRESS
    private val multisig = "tb1qsqeqserfyu34adxys9r05e9qcug90ze0achw4eh0qa4zv44pzkmsrt7hqa"
    private val buyer = "tb1qkhv392rd343eheculeludz0hkvx2j9y0thma4r"

    @Test fun `fee wallet is forbidden`() {
        assertTrue(PayoutAddressGate.isForbidden(feeWallet, feeWallet, multisig))
    }

    @Test fun `the escrow's own multisig is forbidden`() {
        assertTrue(PayoutAddressGate.isForbidden(multisig, feeWallet, multisig))
    }

    @Test fun `fee wallet and multisig match case-insensitively`() {
        assertTrue(PayoutAddressGate.isForbidden(feeWallet.uppercase(), feeWallet, multisig))
        assertTrue(PayoutAddressGate.isForbidden(multisig.uppercase(), feeWallet, multisig))
    }

    @Test fun `blank address is forbidden`() {
        assertTrue(PayoutAddressGate.isForbidden("", feeWallet, multisig))
        assertTrue(PayoutAddressGate.isForbidden("   ", feeWallet, multisig))
    }

    @Test fun `a genuine buyer address is allowed`() {
        assertFalse(PayoutAddressGate.isForbidden(buyer, feeWallet, multisig))
    }

    @Test fun `resolver never returns the multisig`() {
        assertNull(EscrowService.resolveBuyerPayoutAddress("", "", multisig))
        assertNull(EscrowService.resolveBuyerPayoutAddress(null, null, multisig))
        assertNull(EscrowService.resolveBuyerPayoutAddress(multisig, null, multisig))
    }

    @Test fun `resolver returns null when only forbidden candidates exist`() {
        assertNull(EscrowService.resolveBuyerPayoutAddress(feeWallet, feeWallet, multisig))
        assertNull(EscrowService.resolveBuyerPayoutAddress(multisig, feeWallet, multisig))
    }

    @Test fun `resolver still returns a genuine buyer address`() {
        assertEquals(buyer, EscrowService.resolveBuyerPayoutAddress(buyer, null, multisig))
    }
}
