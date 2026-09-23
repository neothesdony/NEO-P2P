package com.neop2p.data.escrow

import org.bitcoinj.base.Coin
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rejection-path coverage for the arbitrator crypto extracted from
 * `EscrowService` (Phase 0b). The configured `NeoP2PConfig.ARBITRATOR_PUBKEY`
 * is an x-only pubkey whose private key is intentionally not in the repo, so a
 * positive sign→verify round trip cannot be constructed in-JVM; the happy path
 * is exercised end-to-end on-device (Settings → Dispute Feed resolves a dispute).
 * What is proven here:
 *   - a signature from any OTHER key is rejected (the pubkey gate is real);
 *   - every blank/malformed input is rejected without throwing;
 *   - a non-arbitrator private key cannot sign.
 */
class ArbitratorSignerTest {

    private val net: NetworkParameters = TestNet3Params.get()
    private val buyerKey = ECKey()
    private val sellerKey = ECKey()
    private val spareKey = ECKey()

    private fun redeemScript(): Script =
        ScriptBuilder.createRedeemScript(2, listOf(buyerKey, sellerKey, ECKey.fromPrivate(ByteArray(32) { 1 })))

    private fun unsignedTx(valueSats: Long = 90_000L): Transaction {
        val tx = Transaction(net)
        tx.addInput(Sha256Hash.wrap("bb".repeat(32)), 0L, ScriptBuilder.createEmpty())
        tx.addOutput(Coin.valueOf(valueSats), LegacyAddress.fromKey(net, buyerKey))
        return tx
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun signedByOtherKey(tx: Transaction, script: Script): String {
        val hash = tx.hashForSignature(0, script, Transaction.SigHash.ALL, false)
        val sig = spareKey.sign(hash).encodeToDER() + byteArrayOf(Transaction.SigHash.ALL.value.toByte())
        return hex(sig)
    }

    @Test
    fun `verify rejects a signature made by a key that is not the arbitrator`() {
        val tx = unsignedTx()
        val script = redeemScript()
        assertFalse(
            ArbitratorSigner.verify(hex(tx.bitcoinSerialize()), hex(script.program), signedByOtherKey(tx, script))
        )
    }

    @Test
    fun `verify rejects null, blank and malformed input without throwing`() {
        val tx = unsignedTx()
        val script = redeemScript()
        val txHex = hex(tx.bitcoinSerialize())
        val scriptHex = hex(script.program)

        assertFalse(ArbitratorSigner.verify(null, scriptHex, "00"))
        assertFalse(ArbitratorSigner.verify(txHex, scriptHex, ""))
        assertFalse(ArbitratorSigner.verify("zz", scriptHex, "00"))
        assertFalse(ArbitratorSigner.verify(txHex, "zz", "00"))
        assertFalse(ArbitratorSigner.verify(txHex, scriptHex, "not-hex-signature"))
    }

    @Test
    fun `verify rejects a signature over a different transaction`() {
        val script = redeemScript()
        val sigHex = signedByOtherKey(unsignedTx(), script)
        // Same redeem script, different output value → different sighash.
        val tampered = hex(unsignedTx(valueSats = 90_001L).bitcoinSerialize())
        assertFalse(ArbitratorSigner.verify(tampered, hex(script.program), sigHex))
    }

    @Test
    fun `sign refuses a private key that is not the arbitrator`() {
        val tx = unsignedTx()
        val script = redeemScript()
        val result = ArbitratorSigner.sign(
            hex(tx.bitcoinSerialize()), hex(script.program), spareKey.privKeyBytes
        )
        assertTrue("expected failure for a non-arbitrator key", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }
}
