package com.neop2p.data.escrow

import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptOpCodes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowRecoveryTxTest {

    private val net: NetworkParameters = TestNet3Params.get()
    private val seller = ECKey.fromPrivate(ByteArray(32) { 2 })
    private val sellerAddr: String = LegacyAddress.fromKey(net, seller).toBase58()
    private val fundingTxid = "ab".repeat(32)

    @Test
    fun `recovery tx sets locktime and non-final sequence`() {
        val tx = EscrowRecoveryTx.build(fundingTxid, 0, 1_000_000, 1_790_000_000, sellerAddr, 2_000, 10_000, net)
        assertEquals(1_790_000_000L, tx.lockTime)
        assertEquals(0xfffffffeL, tx.getInput(0).sequenceNumber)
        assertEquals(sellerAddr, tx.getOutput(0).scriptPubKey.getToAddress(net).toString())
        assertEquals(1, tx.getOutputs().size)
    }

    @Test
    fun `recovery fee cannot exceed the ceiling`() {
        assertThrows(IllegalArgumentException::class.java) {
            EscrowRecoveryTx.build(fundingTxid, 0, 1_000_000, 1_790_000_000, sellerAddr, 50_000, 10_000, net)
        }
    }

    @Test
    fun `negative fee and non-positive output are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            EscrowRecoveryTx.build(fundingTxid, 0, 1_000_000, 1_790_000_000, sellerAddr, -1, 10_000, net)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EscrowRecoveryTx.build(fundingTxid, 0, 1_000_000, 1_790_000_000, sellerAddr, 1_000_000, 10_000, net)
        }
    }

    @Test
    fun `P2SH recovery spend selects the IF branch and verifies`() {
        val locktime = 1_790_000_000L
        val redeem = EscrowScripts.build(
            EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, ECKey(), seller, ECKey(), locktime
        )
        val tx = EscrowRecoveryTx.build(fundingTxid, 0, 1_000_000, locktime, sellerAddr, 2_000, 10_000, net)

        val spend = EscrowRecoveryTx.spendParts(tx, redeem, seller, 1_000_000, witness = false)

        assertNotNull(spend)
        assertNull(spend!!.witness)
        val chunks = spend.scriptSig!!.chunks
        assertEquals("sig, OP_1, redeem", 3, chunks.size)
        assertTrue(chunks[1].equalsOpCode(ScriptOpCodes.OP_1))
        assertArrayEquals(redeem.getProgram(), chunks[2].data)
        assertTrue(
            EscrowTxBuilder.verifySignature(
                tx, redeem, seller.publicKeyAsHex, chunks[0].data!!, 1_000_000, witness = false
            )
        )
    }

    @Test
    fun `P2WSH recovery spend uses the witness stack and verifies`() {
        val locktime = 1_790_000_000L
        val redeem = EscrowScripts.build(
            EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, ECKey(), seller, ECKey(), locktime
        )
        val tx = EscrowRecoveryTx.build(fundingTxid, 0, 1_000_000, locktime, sellerAddr, 2_000, 10_000, net)

        val spend = EscrowRecoveryTx.spendParts(tx, redeem, seller, 1_000_000, witness = true)

        assertNotNull(spend)
        assertNull(spend!!.scriptSig)
        val w = spend.witness!!
        assertEquals(3, w.pushCount)
        assertArrayEquals(byteArrayOf(0x01), w.getPush(1))
        assertArrayEquals(redeem.getProgram(), w.getPush(2))
        assertTrue(
            EscrowTxBuilder.verifySignature(
                tx, redeem, seller.publicKeyAsHex, w.getPush(0), 1_000_000, witness = true
            )
        )
    }
}
