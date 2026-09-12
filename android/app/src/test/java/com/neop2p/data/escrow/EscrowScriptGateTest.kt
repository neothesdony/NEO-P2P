package com.neop2p.data.escrow

import org.bitcoinj.core.*
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowScriptGateTest {
    private val net: NetworkParameters = TestNet3Params.get()
    private val buyer = ECKey(); private val seller = ECKey(); private val arb = ECKey(); private val fakeArb = ECKey()
    private val arbXOnly = arb.publicKeyAsHex.substring(2)

    private fun script(vararg keys: ECKey) = ScriptBuilder.createRedeemScript(2, keys.toList())
    private fun p2sh(s: Script) = LegacyAddress.fromScriptHash(net, Utils.sha256hash160(s.program)).toBase58()
    private fun p2wsh(s: Script) = SegwitAddress.fromProgram(net, 0, Sha256Hash.hash(s.program)).toBech32()

    @Test fun `valid script passes for both carriers`() {
        val s = script(buyer, seller, arb)
        assertTrue(EscrowScriptGate.verify(s.program.toHex(), p2sh(s), "LEGACY", arbXOnly, net).ok)
        assertTrue(EscrowScriptGate.verify(s.program.toHex(), p2wsh(s), "SEGWIT", arbXOnly, net).ok)
    }

    @Test fun `fake arb key in script fails`() {
        val s = script(buyer, seller, fakeArb)
        val v = EscrowScriptGate.verify(s.program.toHex(), p2sh(s), "LEGACY", arbXOnly, net)
        assertFalse(v.ok); assertFalse(v.arbKeyInScript); assertTrue(v.addressMatches)
    }

    @Test fun `address mismatch fails`() {
        val s = script(buyer, seller, arb)
        val other = script(ECKey(), ECKey(), arb)
        val v = EscrowScriptGate.verify(s.program.toHex(), p2sh(other), "LEGACY", arbXOnly, net)
        assertFalse(v.ok); assertTrue(v.arbKeyInScript); assertFalse(v.addressMatches)
    }

    @Test fun `garbage input fails closed`() {
        assertFalse(EscrowScriptGate.verify("zz", "bad", "LEGACY", arbXOnly, net).ok)
    }
}
