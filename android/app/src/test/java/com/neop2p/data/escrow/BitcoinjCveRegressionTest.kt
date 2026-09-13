package com.neop2p.data.escrow

import org.bitcoinj.base.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * CVE-2026-44714 / GHSA-hfcf-v2f8-x9pc fail-closed regression.
 *
 * Pre-0.17.1, the P2PKH fast path of correctlySpends() verified an
 * attacker-supplied (sig, pubkey) pair but never checked
 * HASH160(pubkey) == extractHashFromP2PKH(scriptPubKey), so ANY keypair
 * "satisfied" a spend of ANY P2PKH output. 0.17.1 enforces the binding.
 *
 * The fixture is deliberately realistic: the attacker signature is VALID over
 * the P2PKH sighash for the attacker's own key, so pre-fix the fast path accepts
 * it and returns (the bug); post-fix the missing hash commitment check rejects
 * it. This test failed on 0.16.2 and passes on 0.17.1, keeping the dependency out
 * of the affected range.
 *
 * It targets the vulnerable fast path precisely: the 6-arg witness overload of
 * `Script.correctlySpends` invoked on the scriptSig. The deprecated 4-arg
 * overload (used by the lower-level interpreter in
 * EscrowSegwitSpendForensicsTest) runs the full OP_DUP/OP_HASH160/OP_EQUALVERIFY
 * sequence and would NOT exhibit the fast-path bug.
 *
 * In 0.16.2 this code lived in `Script.java`; the 0.17.x refactor moved the same
 * logic into `ScriptExecution.correctlySpends()` (the advisory's named location).
 */
class BitcoinjCveRegressionTest {

    private val params = TestNet3Params.get()

    @Test
    fun `P2PKH spend with a pubkey not committed by the output is rejected`() {
        Context.getOrCreate(params)

        val victim = ECKey()      // owns the output
        val attacker = ECKey()    // signs; pubkey does NOT hash to victim's P2PKH
        val scriptPubKey: Script = ScriptBuilder.createP2PKHOutputScript(victim)

        // Fund a synthetic output to the victim's P2PKH script.
        val funding = Transaction(params)
        funding.addOutput(Coin.valueOf(100_000L), scriptPubKey)

        // Spend it with the attacker's key: scriptSig = [attacker_sig, attacker_pubkey].
        val spend = Transaction(params)
        spend.addInput(funding.getTxId(), 0L, ScriptBuilder.createEmpty())
        spend.addOutput(Coin.valueOf(99_000L), ScriptBuilder.createP2PKHOutputScript(victim))

        val attackerSig = spend.calculateSignature(
            0, attacker, scriptPubKey, Transaction.SigHash.ALL, false
        )
        val inputScript = ScriptBuilder.createInputScript(attackerSig, attacker)
        spend.replaceInput(0, spend.getInput(0).withScriptSig(inputScript))

        assertThrows(ScriptException::class.java) {
            // The vulnerable P2PKH fast path: invoked on the scriptSig, with the
            // spent output's scriptPubKey. witness/value are unused for P2PKH.
            inputScript.correctlySpends(
                spend, 0, null, null, scriptPubKey, Script.ALL_VERIFY_FLAGS
            )
        }
    }
}
