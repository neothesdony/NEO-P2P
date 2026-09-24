package com.neop2p.data.escrow

import org.bitcoinj.crypto.ECKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowCltvGateTest {
    private val buyer = ECKey(); private val seller = ECKey(); private val arb = ECKey()
    private val created = 1_700_000_000_000L
    private val maturity = EscrowScripts.MATURITY_MS
    private val honestLock = EscrowScripts.locktimeFor(created)

    private fun v1(locktime: Long) =
        EscrowScripts.build(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, buyer, seller, arb, locktime).program

    @Test fun `honest maturity passes`() {
        val v = EscrowCltvGate.verify(v1(honestLock), seller.publicKeyAsHex, created, maturity)
        assertTrue(v.ok); assertTrue(v.sellerKeyMatches); assertTrue(v.locktimeValid)
    }

    @Test fun `past locktime is rejected`() {
        val v = EscrowCltvGate.verify(v1(1L), seller.publicKeyAsHex, created, maturity)
        assertFalse(v.locktimeValid); assertFalse(v.ok)
    }

    @Test fun `locktime below maturity floor is rejected`() {
        val v = EscrowCltvGate.verify(v1(honestLock - 1), seller.publicKeyAsHex, created, maturity)
        assertFalse(v.locktimeValid); assertFalse(v.ok)
    }

    @Test fun `seller key mismatch is rejected`() {
        val v = EscrowCltvGate.verify(v1(honestLock), ECKey().publicKeyAsHex, created, maturity)
        assertTrue(v.locktimeValid); assertFalse(v.sellerKeyMatches); assertFalse(v.ok)
    }

    @Test fun `garbage fails closed`() {
        assertFalse(EscrowCltvGate.verify(ByteArray(0), seller.publicKeyAsHex, created, maturity).ok)
        assertFalse(EscrowCltvGate.verify(byteArrayOf(0x51), seller.publicKeyAsHex, created, maturity).ok)
    }
}
