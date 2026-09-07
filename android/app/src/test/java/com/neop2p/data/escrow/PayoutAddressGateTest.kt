package com.neop2p.data.escrow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayoutAddressGateTest {

    private val feeWallet = "tb1q05q8yd60j5ujlqwyfc978jynx9mgpk2l23fg09"
    private val multisig = "tb1qsqeqserfyu34adxys9r05e9qcug90ze0achw4eh0qa4zv44pzkmsrt7hqa"

    @Test
    fun `fee wallet is forbidden`() {
        assertTrue(PayoutAddressGate.isForbidden(feeWallet, feeWallet, multisig))
    }

    @Test
    fun `fee wallet comparison is case-insensitive`() {
        assertTrue(PayoutAddressGate.isForbidden(feeWallet.uppercase(), feeWallet, multisig))
    }

    @Test
    fun `the escrow's own funding address is forbidden`() {
        assertTrue(PayoutAddressGate.isForbidden(multisig, feeWallet, multisig))
    }

    @Test
    fun `blank address is forbidden`() {
        assertTrue(PayoutAddressGate.isForbidden("", feeWallet, multisig))
        assertTrue(PayoutAddressGate.isForbidden("   ", feeWallet, multisig))
    }

    @Test
    fun `a normal buyer address is allowed`() {
        assertFalse(PayoutAddressGate.isForbidden("tb1qkhv392rd343eheculeludz0hkvx2j9y0thma4r", feeWallet, multisig))
    }

    @Test
    fun `null funding address does not forbid anything extra`() {
        assertFalse(PayoutAddressGate.isForbidden("tb1qkhv392rd343eheculeludz0hkvx2j9y0thma4r", feeWallet, null))
    }

    @Test
    fun `whitespace-padded fee wallet is still forbidden`() {
        assertTrue(PayoutAddressGate.isForbidden("  $feeWallet  ", feeWallet, multisig))
    }
}
