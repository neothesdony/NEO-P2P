package com.neop2p.data.wallet

import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.SegwitAddress
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the "send to escrow" mempool rejection
 * (sendrawtransaction RPC error -26 mempool-script-verify-flag).
 *
 * Root cause (confirmed against bitcoinj v0.16.2 source):
 * WalletService.send() signed P2WPKH inputs with the OUTPUT script
 * (`OP_0 <hash160>`) as the BIP-143 scriptCode, but BIP-143 requires the
 * scriptCode to be the *P2PKH script* (`OP_DUP OP_HASH160 <hash> ...`).
 * bitcoinj's own Transaction.addSignedInput() does exactly this:
 *
 *   else if (ScriptPattern.isP2WPKH(scriptPubKey)) {
 *       Script scriptCode = ScriptBuilder.createP2PKHOutputScript(sigKey);
 *       ...calculateWitnessSignature(inputIndex, sigKey, scriptCode, ...);
 *
 * A signature over the wrong scriptCode fails script verification at the
 * node → mempool reject → "Explorer returned unexpected broadcast response".
 */
class WalletSegwitSigningTest {

    private val params = TestNet3Params.get()
    private val privKey = ByteArray(32) { 1 }

    @Test
    fun `wallet key from private must be compressed (address derivation contract)`() {
        val key = ECKey.fromPrivate(privKey)
        assertTrue(
            "ECKey.fromPrivate(privKey) must produce a COMPRESSED key: wallet/escrow " +
                "addresses are derived from the compressed pubkey (BIP-32 HD keys are always compressed)",
            key.isCompressed
        )
    }

    @Test
    fun `signature committed to the OP_0 program does NOT verify (the bug)`() {
        val key = ECKey.fromPrivate(privKey)
        val segwitAddr = SegwitAddress.fromKey(params, key)

        // Exactly what WalletService.send() did before the fix:
        val program = ScriptBuilder.createOutputScript(segwitAddr) // OP_0 <hash160>
        val tx = Transaction(params)
        tx.addInput(Sha256Hash.ZERO_HASH, 0, ScriptBuilder.createEmpty())
        tx.addOutput(Coin.valueOf(50_000), program)
        val inputValue = Coin.valueOf(50_000)
        val txSig = tx.calculateWitnessSignature(
            0, key, program, inputValue, Transaction.SigHash.ALL, false
        )

        // What the node will verify (BIP-143 P2WPKH): scriptCode = P2PKH script.
        val correctScriptCode = ScriptBuilder.createP2PKHOutputScript(key)
        val correctHash = tx.hashForWitnessSignature(
            0, correctScriptCode, inputValue, Transaction.SigHash.ALL, false
        )
        assertFalse(
            "signature committed to OP_0 program must NOT verify against the P2PKH " +
                "scriptCode — this is the bug that produced -mempool-script-verify-flag-",
            key.verify(correctHash, txSig)
        )
    }

    @Test
    fun `signature committed to the P2PKH scriptCode verifies (the fix)`() {
        val key = ECKey.fromPrivate(privKey)
        val segwitAddr = SegwitAddress.fromKey(params, key)

        // The fix: scriptCode = P2PKH script, exactly like bitcoinj's own
        // Transaction.addSignedInput() does for P2WPKH.
        val scriptCode = ScriptBuilder.createP2PKHOutputScript(key)
        val tx = Transaction(params)
        tx.addInput(Sha256Hash.ZERO_HASH, 0, ScriptBuilder.createEmpty())
        tx.addOutput(Coin.valueOf(50_000), ScriptBuilder.createOutputScript(segwitAddr))
        val inputValue = Coin.valueOf(50_000)
        val txSig = tx.calculateWitnessSignature(
            0, key, scriptCode, inputValue, Transaction.SigHash.ALL, false
        )

        val correctHash = tx.hashForWitnessSignature(
            0, scriptCode, inputValue, Transaction.SigHash.ALL, false
        )
        assertTrue(
            "signature committed to the P2PKH scriptCode must verify against the " +
                "BIP-143 hash — this is what the node checks",
            key.verify(correctHash, txSig)
        )
    }

    @Test
    fun `legacy P2PKH signing still verifies (control)`() {
        val key = ECKey.fromPrivate(privKey)
        val legacyAddr = LegacyAddress.fromKey(params, key)
        val outputScript = ScriptBuilder.createOutputScript(legacyAddr)
        val tx = Transaction(params)
        tx.addInput(Sha256Hash.ZERO_HASH, 0, ScriptBuilder.createEmpty())
        tx.addOutput(Coin.valueOf(50_000), outputScript)
        val hash = tx.hashForSignature(0, outputScript, Transaction.SigHash.ALL, false)
        val sig = key.sign(hash)
        assertTrue("P2PKH control signature must verify", key.verify(hash, sig))
    }
}
