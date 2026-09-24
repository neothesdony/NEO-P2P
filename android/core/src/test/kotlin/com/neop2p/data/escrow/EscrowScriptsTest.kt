package com.neop2p.data.escrow

import com.neop2p.domain.model.BitcoinAddressType
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.crypto.internal.CryptoUtils
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptOpCodes
import org.junit.Assert.*
import org.junit.Test

class EscrowScriptsTest {

    private val buyer = ECKey.fromPrivate(ByteArray(32) { 1 })
    private val seller = ECKey.fromPrivate(ByteArray(32) { 2 })
    private val arb = ECKey.fromPrivate(ByteArray(32) { 3 })
    private val net = TestNet3Params.get()

    private fun commitsPubKey(script: Script, key: ECKey): Boolean =
        script.chunks.any { it.data?.contentEquals(key.pubKey) == true }

    @Test
    fun `v1 script detects as cltv template and v0 as multisig`() {
        val v1 = EscrowScripts.build(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, buyer, seller, arb, 1_790_000_000L)
        val v0 = EscrowScripts.build(EscrowScriptTemplate.MULTISIG_2OF3_V0, buyer, seller, arb, 0L)
        assertEquals(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, EscrowScriptTemplate.detect(v1.getProgram()))
        assertEquals(EscrowScriptTemplate.MULTISIG_2OF3_V0, EscrowScriptTemplate.detect(v0.getProgram()))
        assertNull(EscrowScriptTemplate.detect(byteArrayOf(0x51)))
    }

    @Test
    fun `v1 commits the locktime and all three keys`() {
        val v1 = EscrowScripts.build(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, buyer, seller, arb, 1_790_000_000L)
        val hex = v1.getProgram().joinToString("") { "%02x".format(it) }
        assertTrue(hex.contains(EscrowScriptTemplate.CLTV_OPCODE_HEX)) // 0xb1 CHECKLOCKTIMEVERIFY
        // 1_790_000_000 (0x6ab13b80) minimally encoded: 04 80 3b b1 6a.
        assertTrue(hex.contains("04803bb16a"))
        assertTrue(commitsPubKey(v1, buyer))
        assertTrue(commitsPubKey(v1, seller))
        assertTrue(commitsPubKey(v1, arb))
    }

    @Test
    fun `locktime value is parameterized and minimally encoded`() {
        val a = EscrowScripts.build(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, buyer, seller, arb, 1_790_000_000L)
        val b = EscrowScripts.build(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, buyer, seller, arb, 1_600_000_000L)
        val ah = a.getProgram().toHex()
        val bh = b.getProgram().toHex()
        assertTrue(ah.contains("04803bb16a")) // 1_790_000_000 (0x6ab13b80)
        assertTrue(bh.contains("0400105e5f")) // 1_600_000_000 (0x5f5e1000)
        assertNotEquals(ah, bh)
    }

    @Test
    fun `near-miss cltv lookalikes are rejected`() {
        val withByte = ScriptBuilder()
            .op(ScriptOpCodes.OP_IF)
            .data(byteArrayOf(0xb1.toByte()))
            .op(ScriptOpCodes.OP_ENDIF)
            .build()
        assertNull(EscrowScriptTemplate.detect(withByte.getProgram()))
        // 0x0b 0x10 — "b1" straddles the nibble boundary.
        val nibbleBoundary = ScriptBuilder()
            .op(ScriptOpCodes.OP_IF)
            .data(byteArrayOf(0x0b, 0x10))
            .op(ScriptOpCodes.OP_ENDIF)
            .build()
        assertNull(EscrowScriptTemplate.detect(nibbleBoundary.getProgram()))
    }

    @Test
    fun `address is deterministic and type-correct`() {
        val script = EscrowScripts.build(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, buyer, seller, arb, 1_790_000_000L)
        val legacy = EscrowScripts.address(script, BitcoinAddressType.LEGACY, net)
        val segwit = EscrowScripts.address(script, BitcoinAddressType.SEGWIT, net)
        assertEquals(
            CryptoUtils.sha256hash160(script.getProgram()).toHex(),
            LegacyAddress.fromBase58(net, legacy).hash.toHex()
        )
        assertTrue(segwit.startsWith("tb1"))
        assertEquals(legacy, EscrowScripts.address(script, BitcoinAddressType.LEGACY, net))
    }

    @Test
    fun `v1 multisig pubkeys are sorted ascending`() {
        val buyer = ECKey(); val seller = ECKey(); val arb = ECKey()
        val s = EscrowScripts.build(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, buyer, seller, arb, 1_790_000_000L)
        val keys = s.chunks.mapNotNull { it.data?.takeIf { d -> d.size == 33 }?.copyOfRange(1, 33) }
        // chunk 4 is the OP_IF seller key; chunks 8/9/10 are the multisig keys
        val multisig = s.chunks.drop(8).take(3).mapNotNull { it.data }
        assertEquals(3, multisig.size)
        val sorted = multisig.sortedWith { a, b ->
            for (i in 0 until 33) {
                val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (c != 0) return@sortedWith c
            }
            0
        }
        assertEquals(sorted, multisig)
        assertTrue(keys.isNotEmpty())
    }

    @Test
    fun `locktimeFor adds the maturity window`() {
        assertEquals(1_800_000_000L, EscrowScripts.locktimeFor(1_800_000_000_000L - EscrowScripts.MATURITY_MS))
    }
}
