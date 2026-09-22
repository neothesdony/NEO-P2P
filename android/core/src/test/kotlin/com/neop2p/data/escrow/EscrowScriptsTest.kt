package com.neop2p.data.escrow

import com.neop2p.domain.model.BitcoinAddressType
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.crypto.internal.CryptoUtils
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
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
        assertTrue(commitsPubKey(v1, buyer))
        assertTrue(commitsPubKey(v1, seller))
        assertTrue(commitsPubKey(v1, arb))
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
    fun `locktimeFor adds the maturity window`() {
        assertEquals(1_800_000_000L, EscrowScripts.locktimeFor(1_800_000_000_000L - EscrowScripts.MATURITY_MS))
    }
}
