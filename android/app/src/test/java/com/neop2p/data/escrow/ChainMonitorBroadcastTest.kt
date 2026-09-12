package com.neop2p.data.escrow

import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for broadcast-response verification (audit P2-1, 2026-09-12).
 *
 * The explorer's POST /api/tx response used to be trusted verbatim. These
 * predicates make the locally computed txid the authority: a body that is not
 * the txid we built is not an acceptance, and a "failed" broadcast is
 * reconciled against the txid we built before it is reported as a failure.
 */
class ChainMonitorBroadcastTest {

    private val txid = "a".repeat(64)

    @Test
    fun `matching txid is accepted`() {
        assertTrue(ChainMonitor.broadcastAccepted(txid, expectedTxid = txid))
    }

    @Test
    fun `matching txid is accepted case-insensitively and with whitespace`() {
        assertTrue(ChainMonitor.broadcastAccepted("  ${txid.uppercase()}  ", expectedTxid = txid))
    }

    @Test
    fun `different txid is rejected`() {
        assertFalse(ChainMonitor.broadcastAccepted("b".repeat(64), expectedTxid = txid))
    }

    @Test
    fun `non-hex or wrong-length body is rejected`() {
        assertFalse(ChainMonitor.broadcastAccepted("Transaction already in block chain", expectedTxid = txid))
        assertFalse(ChainMonitor.broadcastAccepted("abc123", expectedTxid = txid))
        assertFalse(ChainMonitor.broadcastAccepted("", expectedTxid = txid))
    }

    @Test
    fun `without an expectation any 64-hex body is accepted`() {
        assertTrue(ChainMonitor.broadcastAccepted("c".repeat(64), expectedTxid = null))
    }

    @Test
    fun `reconciliation succeeds only for the txid we built`() {
        val info = ChainMonitor.TxInfo(txid = txid, confirmed = false, confirmations = 0L, blockTimeSec = 0L)
        assertTrue(ChainMonitor.reconciledAfterFailure(info, txid))
        assertFalse(ChainMonitor.reconciledAfterFailure(info, "b".repeat(64)))
        assertFalse(ChainMonitor.reconciledAfterFailure(null, txid))
    }

    @Test
    fun `local txid is the explorer-style display order`() {
        // Contract evidence: Transaction.getTxId() wraps hashTwice(serialize) in
        // Sha256Hash.wrapReversed, and Sha256Hash.toString() hex-encodes the
        // stored (display-order) bytes — so getHashAsString() is exactly the
        // value Mempool returns as plain text from POST /api/tx. The digest is
        // recomputed here with MessageDigest so the assertion does not lean on
        // bitcoinj's own wrapper.
        val tx = Transaction(TestNet3Params.get())
        tx.addInput(Sha256Hash.ZERO_HASH, 0, ScriptBuilder.createEmpty())
        tx.addOutput(Coin.valueOf(50_000L), ScriptBuilder.createP2PKHOutputScript(
            org.bitcoinj.core.ECKey.fromPrivate(ByteArray(32) { 1 })
        ))

        val expected = sha256d(tx.bitcoinSerialize()).reversed()
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, tx.getHashAsString())
        assertEquals(tx.getTxId().toString(), tx.getHashAsString())
    }

    /** sha256(sha256(bytes)) — the txid preimage, independent of bitcoinj. */
    private fun sha256d(bytes: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(bytes))
    }
}
