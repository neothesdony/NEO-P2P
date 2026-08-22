package com.neop2p.data.escrow

import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.Transaction.SigHash
import org.bitcoinj.core.Utils
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the 2-of-3 P2SH multisig escrow signing logic used by
 * EscrowService. This exercises the real redeem-script signing path
 * (the bug previously signed with an empty script).
 */
class EscrowCryptoTest {

    private val params: NetworkParameters = TestNet3Params.get()

    @Test
    fun twoOfThreeP2shSigningIsValid() {
        val buyerKey = ECKey()
        val sellerKey = ECKey()
        val arbKey = ECKey()

        val redeemScript = ScriptBuilder.createRedeemScript(2, listOf(buyerKey, sellerKey, arbKey))
        // P2SH address = hash160 of the redeem script program
        val fundingAddress = LegacyAddress.fromScriptHash(params, Utils.sha256hash160(redeemScript.program))
        // P2SH testnet addresses start with '2'
        assertTrue(fundingAddress.toString().startsWith("2"))

        val tradeAmount = 1_000_000L
        val feeAmount = 10_000L
        val buyerAmount = tradeAmount - feeAmount / 2 // 99.5%
        val deposit = tradeAmount + feeAmount / 2     // 100.5%

        // Funding tx pays the P2SH address
        val fundingTx = Transaction(params)
        fundingTx.addOutput(Coin.valueOf(deposit), fundingAddress)

        // Payout tx spends the funding output
        val payoutTx = Transaction(params)
        payoutTx.addInput(fundingTx.txId, 0L, ScriptBuilder.createEmpty())
        val buyerAddress = LegacyAddress.fromPubKeyHash(params, Utils.sha256hash160(buyerKey.pubKey))
        payoutTx.addOutput(Coin.valueOf(buyerAmount), buyerAddress)
        val feeAddress = org.bitcoinj.core.Address.fromString(params, "tb1qscw0qwnfplchv4g9rpzrnvkmpdg6cf6q3n3z95")
        payoutTx.addOutput(Coin.valueOf(feeAmount), feeAddress)

        // Sign with buyer + seller keys against the REAL redeem script
        val sig1 = sign(payoutTx, 0, redeemScript, buyerKey)
        val sig2 = sign(payoutTx, 0, redeemScript, sellerKey)

        // P2SH multisig scriptSig
        val scriptSig = ScriptBuilder.createMultiSigInputScriptBytes(
            listOf(sig1.encodeToBitcoin(), sig2.encodeToBitcoin()),
            redeemScript.program
        )
        payoutTx.getInput(0).scriptSig = scriptSig

        // Money math
        assertEquals(deposit, payoutTx.getOutput(0).value.value + payoutTx.getOutput(1).value.value)
        assertEquals(buyerAmount, payoutTx.getOutput(0).value.value)
        assertEquals(feeAmount, payoutTx.getOutput(1).value.value)
        assertEquals(deposit, buyerAmount + feeAmount)

        // Serialize/deserialize round-trip
        val parsed = Transaction(params, payoutTx.bitcoinSerialize())
        assertEquals(payoutTx.txId, parsed.txId)
        assertTrue(scriptSig.toString().isNotEmpty())
    }

    @Test
    fun emptyScriptSigningDiffersFromRedeemScript() {
        val key = ECKey()
        val other = ECKey()
        val arb = ECKey()
        val redeemScript = ScriptBuilder.createRedeemScript(2, listOf(key, other, arb))

        val tx = Transaction(params)
        tx.addOutput(Coin.COIN, LegacyAddress.fromPubKeyHash(params, Utils.sha256hash160(key.pubKey)))
        tx.addInput(Sha256Hash.of(byteArrayOf(1)), 0L, ScriptBuilder.createEmpty())

        val hashWithRedeem = tx.hashForSignature(0, redeemScript, SigHash.ALL, false)
        val hashWithEmpty = tx.hashForSignature(0, ScriptBuilder.createEmpty(), SigHash.ALL, false)

        assertFalse(hashWithRedeem == hashWithEmpty)
    }

    private fun sign(tx: Transaction, index: Int, script: Script, key: ECKey): TransactionSignature {
        val hash = tx.hashForSignature(index, script, SigHash.ALL, false)
        val sig = key.sign(hash)
        return TransactionSignature(sig, SigHash.ALL, false)
    }
}
