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

class EscrowScriptGateTest {
    private val net: NetworkParameters = TestNet3Params.get()
    private val buyer = ECKey(); private val seller = ECKey(); private val arb = ECKey(); private val fakeArb = ECKey()
    private val arbXOnly = arb.publicKeyAsHex.substring(2)

    private fun script(vararg keys: ECKey) = ScriptBuilder.createRedeemScript(2, keys.toList())
    private fun mOfN(m: Int, vararg keys: ECKey) = ScriptBuilder.createRedeemScript(m, keys.toList())
    private fun p2sh(s: Script) = LegacyAddress.fromScriptHash(net, CryptoUtils.sha256hash160(s.program)).toBase58()
    private fun p2wsh(s: Script) = SegwitAddress.fromProgram(net, 0, Sha256Hash.hash(s.program)).toBech32()

    @Test fun `valid script passes for both carriers`() {
        val s = script(buyer, seller, arb)
        val legacy = EscrowScriptGate.verify(s.program.toHex(), p2sh(s), "LEGACY", arbXOnly, net)
        assertTrue(legacy.ok); assertTrue(legacy.scriptIs2of3)
        assertTrue(EscrowScriptGate.verify(s.program.toHex(), p2wsh(s), "SEGWIT", arbXOnly, net).ok)
    }

    @Test fun `1-of-3 script fails even with arb key`() {
        val attacker = ECKey()
        val s = mOfN(1, attacker, buyer, arb)
        val v = EscrowScriptGate.verify(s.program.toHex(), p2sh(s), "LEGACY", arbXOnly, net)
        assertFalse(v.ok); assertTrue(v.arbKeyInScript); assertTrue(v.addressMatches); assertFalse(v.scriptIs2of3)
    }

    @Test fun `3-of-3 script fails`() {
        val s = mOfN(3, buyer, seller, arb)
        val v = EscrowScriptGate.verify(s.program.toHex(), p2sh(s), "LEGACY", arbXOnly, net)
        assertFalse(v.ok); assertTrue(v.arbKeyInScript); assertTrue(v.addressMatches); assertFalse(v.scriptIs2of3)
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

    @Test fun `containsKey matches a script slot by compressed or x-only key`() {
        val s = script(buyer, seller, arb)
        val hex = s.program.toHex()
        assertTrue(EscrowScriptGate.containsKey(hex, buyer.publicKeyAsHex))
        assertTrue(EscrowScriptGate.containsKey(hex, buyer.publicKeyAsHex.substring(2)))
        assertFalse(EscrowScriptGate.containsKey(hex, ECKey().publicKeyAsHex))
        assertFalse(EscrowScriptGate.containsKey("", buyer.publicKeyAsHex))
        assertFalse(EscrowScriptGate.containsKey("zz", buyer.publicKeyAsHex))
    }

    @Test fun `v1 script passes the gate`() {
        val s = EscrowScripts.build(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, buyer, seller, arb, 1_790_000_000L)
        val v = EscrowScriptGate.verify(
            s.program.toHex(), p2sh(s), "LEGACY", arbXOnly, net,
            EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1
        )
        assertTrue(v.ok)
        assertTrue(v.templateMatches)
        assertTrue(v.scriptIs2of3)
        assertTrue(v.arbKeyInScript)
        assertTrue(v.addressMatches)
    }

    @Test fun `v0 script is rejected when V1 is expected`() {
        val s = script(buyer, seller, arb)
        val v = EscrowScriptGate.verify(
            s.program.toHex(), p2sh(s), "LEGACY", arbXOnly, net,
            EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1
        )
        assertFalse(v.ok)
        assertFalse(v.templateMatches)
    }

    @Test fun `unknown script is rejected`() {
        val s = Script(byteArrayOf(0x51))
        assertFalse(
            EscrowScriptGate.verify(
                s.program.toHex(), p2sh(s), "LEGACY", arbXOnly, net,
                EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1
            ).ok
        )
        assertFalse(EscrowScriptGate.verify(s.program.toHex(), p2sh(s), "LEGACY", arbXOnly, net).ok)
    }

    @Test fun `address mismatch fails closed`() {
        val s = EscrowScripts.build(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, buyer, seller, arb, 1_790_000_000L)
        val other = EscrowScripts.build(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, ECKey(), seller, arb, 1_790_000_000L)
        val v = EscrowScriptGate.verify(
            s.program.toHex(), p2sh(other), "LEGACY", arbXOnly, net,
            EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1
        )
        assertFalse(v.ok)
        assertTrue(v.templateMatches)
        assertFalse(v.addressMatches)
    }

    @Test fun `containsKey resolves V1 script slots by compressed or x-only key`() {
        val v1 = EscrowScripts.build(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, buyer, seller, arb, 1_790_000_000L)
        val hex = v1.program.toHex()
        assertTrue(EscrowScriptGate.containsKey(hex, buyer.publicKeyAsHex))
        assertTrue(EscrowScriptGate.containsKey(hex, seller.publicKeyAsHex.substring(2)))
        assertTrue(EscrowScriptGate.containsKey(hex, arb.publicKeyAsHex))
        assertFalse(EscrowScriptGate.containsKey(hex, ECKey().publicKeyAsHex))
    }
}
