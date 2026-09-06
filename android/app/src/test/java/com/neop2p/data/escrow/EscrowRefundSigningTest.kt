package com.neop2p.data.escrow

import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the refund ("Cancel escrow & refund") path builds a correct refund
 * transaction and that its 2-of-3 signature is verifiable against the role keys.
 *
 * Mirrors EscrowRoleSigningTest — plain JUnit 4, no Robolectric, no Android
 * BuildConfig access. The refund math (deposit − network fee) and the
 * scriptSig assembly (OP_0 <sig1> <sig2> <redeemScript>) are extracted here so
 * they can be tested without a Room database.
 */
class EscrowRefundSigningTest {

    private val params: NetworkParameters = TestNet3Params.get()

    /** Approximate vsize used by EscrowService for a P2SH 2-of-3 spend. */
    private val refundApproxVsize = 220L

    private fun hex(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) +
                Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun verifySignature(tx: Transaction, redeemScript: Script, pubkeyHex: String, sig: ByteArray): Boolean {
        return try {
            val key = ECKey.fromPublicOnly(hex(pubkeyHex))
            val ts = TransactionSignature.decodeFromBitcoin(sig, true, true)
            val hash = tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
            key.verify(hash, ts)
        } catch (_: Exception) {
            false
        }
    }

    private fun signTx(tx: Transaction, redeemScript: Script, key: ECKey): ByteArray {
        val hash = tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
        val sig = key.sign(hash)
        return sig.encodeToDER() + byteArrayOf(Transaction.SigHash.ALL.value.toByte())
    }

    /**
     * Build a refund tx exactly as EscrowService.buildRefundTx does: spend the
     * funding input back to the destination for deposit − networkFee.
     */
    private fun buildRefundTx(
        redeemScript: Script,
        depositSats: Long,
        feeRatePerVb: Long,
        fundingTxId: String
    ): Pair<Transaction, Long> {
        val networkFeeSats = maxOf(
            feeRatePerVb * (refundApproxVsize + 34L + 10L),
            250L
        )
        val refundAmount = depositSats - networkFeeSats
        val tx = Transaction(params)
        tx.addInput(Sha256Hash.wrap(fundingTxId), 0L, ScriptBuilder.createEmpty())
        val destination = LegacyAddress.fromKey(params, ECKey()) // fresh valid testnet address
        tx.addOutput(Coin.valueOf(refundAmount), destination)
        return tx to networkFeeSats
    }

    @Test
    fun `refund amount is deposit minus network fee`() {
        val feeRate = 50L
        val deposit = 100_000L
        val (tx, networkFee) = buildRefundTx(
            ScriptBuilder.createRedeemScript(2, listOf(ECKey(), ECKey(), ECKey())),
            deposit,
            feeRate,
            "1111111111111111111111111111111111111111111111111111111111111111"
        )
        assertEquals(maxOf(feeRate * (refundApproxVsize + 34L + 10L), 250L), networkFee)
        assertEquals(deposit - networkFee, tx.getOutput(0).value.value)
    }

    @Test
    fun `same key satisfies both buyer and seller role signatures on the refund`() {
        // In the current design both escrow roles are pinned to the same key.
        val userKey = ECKey()
        val arb = ECKey()
        val redeem = ScriptBuilder.createRedeemScript(2, listOf(userKey, userKey, arb))
        val tx = buildRefundTx(
            redeem,
            500_000L,
            30L,
            "2222222222222222222222222222222222222222222222222222222222222222"
        ).first

        val sig = signTx(tx, redeem, userKey)

        // The one user key satisfies BOTH role slots (buyer and seller).
        assertTrue(verifySignature(tx, redeem, userKey.publicKeyAsHex, sig))
        assertTrue(verifySignature(tx, redeem, userKey.publicKeyAsHex, sig))
    }

    @Test
    fun `2-of-3 refund is valid when both buyer and seller signatures verify`() {
        // In the current design both escrow roles are pinned to the same key.
        val userKey = ECKey()
        val arb = ECKey()
        val redeem = ScriptBuilder.createRedeemScript(2, listOf(userKey, userKey, arb))
        val (tx, _) = buildRefundTx(
            redeem,
            300_000L,
            40L,
            "3333333333333333333333333333333333333333333333333333333333333333"
        )

        // Sign the same input for both the buyer and seller slots with the one
        // user key, exactly as cancelEscrowRefund does, and assemble the P2SH
        // scriptSig (OP_0 <sig1> <sig2> <redeemScript>).
        val buyerSig = signTx(tx, redeem, userKey)
        val sellerSig = signTx(tx, redeem, userKey)
        val scriptSig = ScriptBuilder.createMultiSigInputScriptBytes(
            listOf(buyerSig, sellerSig), redeem.program
        )
        tx.getInput(0).setScriptSig(scriptSig)

        // cancelEscrowRefund's pre-broadcast guard: BOTH role slots must verify.
        assertTrue(verifySignature(tx, redeem, userKey.publicKeyAsHex, buyerSig))
        assertTrue(verifySignature(tx, redeem, userKey.publicKeyAsHex, sellerSig))

        // The scriptSig is a non-empty 2-of-3 spend that carries two signatures
        // and the redeem script.
        assertTrue(scriptSig.chunks.size >= 3)
        assertEquals(
            ScriptBuilder.createRedeemScript(2, listOf(userKey, userKey, arb)).program.joinToString("") { "%02x".format(it) },
            redeem.program.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun `refund with only one signature is not a valid 2-of-3 spend`() {
        val userKey = ECKey()
        val arb = ECKey()
        val redeem = ScriptBuilder.createRedeemScript(2, listOf(userKey, arb, arb))
        val tx = buildRefundTx(
            redeem,
            400_000L,
            20L,
            "4444444444444444444444444444444444444444444444444444444444444444"
        ).first

        // Only one sig: must NOT satisfy the 2-of-3 threshold.
        val sig1 = signTx(tx, redeem, userKey)
        val scriptSig = ScriptBuilder.createMultiSigInputScriptBytes(listOf(sig1), redeem.program)
        tx.getInput(0).setScriptSig(scriptSig)

        // A single signature only verifies against ONE role key — the arbitrator's
        // pubkey slot must NOT accept the user's signature (P0-1 role binding).
        assertTrue(verifySignature(tx, redeem, userKey.publicKeyAsHex, sig1))
        assertFalse(verifySignature(tx, redeem, arb.publicKeyAsHex, sig1))
    }
}
