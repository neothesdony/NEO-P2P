package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EscrowPayoutAddressResolverTest {

    private val feeWallet = "bc1qdfs8ucuq8dm3k3tfuzlvhfyevhs0swz4098fwk"
    private val multisig = "tb1qsqeqserfyu34adxys9r05e9qcug90ze0achw4eh0qa4zv44pzkmsrt7hqa"
    private val buyer = "tb1qkhv392rd343eheculeludz0hkvx2j9y0thma4r"

    @Test
    fun `escrow row wins over offer row`() {
        assertEquals(
            buyer,
            EscrowService.resolveBuyerPayoutAddress(buyer, "tb1qother", multisig)
        )
    }

    @Test
    fun `offer row fills a blank escrow row`() {
        assertEquals(
            buyer,
            EscrowService.resolveBuyerPayoutAddress("", buyer, multisig)
        )
        assertEquals(
            buyer,
            EscrowService.resolveBuyerPayoutAddress(null, buyer, multisig)
        )
    }

    @Test
    fun `never falls back to the multisig funding address`() {
        // The 2026-09-07 Trade A bug: blank buyer address + multisig fallback
        // paid 400k back into the escrow.
        assertNull(EscrowService.resolveBuyerPayoutAddress("", "", multisig))
        assertNull(EscrowService.resolveBuyerPayoutAddress(null, null, multisig))
    }

    @Test
    fun `fee wallet and multisig resolve to null`() {
        assertNull(EscrowService.resolveBuyerPayoutAddress(feeWallet, null, multisig))
        assertNull(EscrowService.resolveBuyerPayoutAddress(null, feeWallet, multisig))
        assertNull(EscrowService.resolveBuyerPayoutAddress(multisig, null, multisig))
    }
}
