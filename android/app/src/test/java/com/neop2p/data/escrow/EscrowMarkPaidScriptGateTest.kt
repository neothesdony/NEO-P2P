package com.neop2p.data.escrow

import org.bitcoinj.base.*
import org.bitcoinj.core.*
import org.bitcoinj.crypto.*
import org.bitcoinj.crypto.internal.CryptoUtils
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-03 (2026-09-15): `markPaid` must fail closed when the escrow's redeem
 * script is unverifiable. A null verdict (no stored script) or a failed
 * [EscrowScriptGate] verdict must refuse the fiat payment; only a fully OK
 * 2-of-3 script containing the official arbitrator key passes.
 */
class EscrowMarkPaidScriptGateTest {

    private val net: NetworkParameters = TestNet3Params.get()
    private val buyer = ECKey()
    private val seller = ECKey()
    private val arb = ECKey()
    private val fakeArb = ECKey()
    private val arbXOnly = arb.publicKeyAsHex.substring(2)

    private fun script(vararg keys: ECKey) = ScriptBuilder.createRedeemScript(2, keys.toList())
    private fun p2sh(s: Script) = LegacyAddress.fromScriptHash(net, CryptoUtils.sha256hash160(s.program)).toBase58()

    private fun verdict(
        redeemScriptHex: String,
        fundingAddress: String,
        arbKey: String = arbXOnly,
    ) = EscrowScriptGate.verify(redeemScriptHex, fundingAddress, "LEGACY", arbKey, net)

    @Test fun `null verdict fails closed`() {
        assertFalse(EscrowService.markPaidScriptGateAllows(null))
    }

    @Test fun `non 2-of-3 verdict fails closed`() {
        val s = ScriptBuilder.createRedeemScript(1, listOf(buyer, seller, arb))
        assertFalse(EscrowService.markPaidScriptGateAllows(verdict(s.program.toHex(), p2sh(s))))
    }

    @Test fun `wrong arbitrator key fails closed`() {
        val s = script(buyer, seller, fakeArb)
        assertFalse(EscrowService.markPaidScriptGateAllows(verdict(s.program.toHex(), p2sh(s))))
    }

    @Test fun `address mismatch fails closed`() {
        val s = script(buyer, seller, arb)
        val other = script(ECKey(), ECKey(), arb)
        assertFalse(EscrowService.markPaidScriptGateAllows(verdict(s.program.toHex(), p2sh(other))))
    }

    @Test fun `garbage input fails closed`() {
        assertFalse(EscrowService.markPaidScriptGateAllows(verdict("zz", "bad")))
    }

    @Test fun `a valid attested 2-of-3 script allows markPaid`() {
        val s = script(buyer, seller, arb)
        assertTrue(EscrowService.markPaidScriptGateAllows(verdict(s.program.toHex(), p2sh(s))))
    }
}
