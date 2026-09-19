package com.neop2p.data.escrow

import org.bitcoinj.base.Coin
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the hex / x-only / raw-tx helpers extracted from
 * `EscrowService` (Phase 0b, commit `41bbdec`). `EscrowCodec` is the single
 * implementation now used by `EscrowService`, [ArbitratorSigner] and
 * `EscrowTxBuilder` on the money path.
 */
class EscrowCodecTest {

    private val net: NetworkParameters = TestNet3Params.get()

    @Test
    fun `hexToBytes parses lower and upper case`() {
        assertArrayEquals(byteArrayOf(0x0a, 0xff.toByte(), 0x00), EscrowCodec.hexToBytes("0aFF00"))
        assertArrayEquals(byteArrayOf(0xde.toByte(), 0xad.toByte()), EscrowCodec.hexToBytes("dead"))
    }

    @Test
    fun `xOnlyToCompressed prefixes a 32-byte key and passes through other sizes`() {
        val xOnly = "cd6cc03ba085ba134ce742998d84980103a7c77d85c42631cd154064aa0d3fba"
        val compressed = EscrowCodec.xOnlyToCompressed(xOnly)
        assertEquals(33, compressed.size)
        assertEquals(0x02.toByte(), compressed[0])
        assertArrayEquals(EscrowCodec.hexToBytes(xOnly), compressed.copyOfRange(1, 33))

        // A 33-byte compressed key is returned unchanged.
        assertArrayEquals(compressed, EscrowCodec.xOnlyToCompressed(compressed.joinToString("") { "%02x".format(it) }))
    }

    @Test
    fun `xOnlyOf drops the prefix of a 33-byte key`() {
        val key = ECKey()
        assertEquals(key.publicKeyAsHex.substring(2), EscrowCodec.xOnlyOf(key.publicKeyAsHex))
        // Non-33-byte input passes through.
        assertEquals("abcd", EscrowCodec.xOnlyOf("abcd"))
    }

    @Test
    fun `parseTx round-trips a serialized transaction`() {
        val tx = Transaction(net)
        tx.addInput(Sha256Hash.wrap("aa".repeat(32)), 0L, ScriptBuilder.createEmpty())
        tx.addOutput(Coin.valueOf(50_000L), LegacyAddress.fromKey(net, ECKey()))
        val hex = tx.bitcoinSerialize().joinToString("") { "%02x".format(it) }
        assertEquals(tx.getTxId(), EscrowCodec.parseTx(hex).getTxId())
    }

    @Test
    fun `compareBytes orders unsigned ascending then by length`() {
        assertTrue(EscrowCodec.compareBytes(byteArrayOf(0x01), byteArrayOf(0x02)) < 0)
        // 0xff must sort AFTER 0x01 (unsigned), unlike a signed Byte compare.
        assertTrue(EscrowCodec.compareBytes(byteArrayOf(0x01), byteArrayOf(0xff.toByte())) < 0)
        assertEquals(0, EscrowCodec.compareBytes(byteArrayOf(0x01, 0x02), byteArrayOf(0x01, 0x02)))
        assertTrue(EscrowCodec.compareBytes(byteArrayOf(0x01), byteArrayOf(0x01, 0x00)) < 0)
    }

    @Test
    fun `blank verification input never throws`() {
        // EscrowCodec.hexToBytes("") is empty; the signer guards null/blank before parsing.
        assertArrayEquals(ByteArray(0), EscrowCodec.hexToBytes(""))
        assertFalse(ArbitratorSigner.verify(null, "", ""))
        assertFalse(ArbitratorSigner.verify("", "", ""))
    }
}
