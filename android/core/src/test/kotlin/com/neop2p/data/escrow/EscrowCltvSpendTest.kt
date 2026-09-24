package com.neop2p.data.escrow

import com.neop2p.NeoP2PConfig
import com.neop2p.domain.model.BitcoinAddressType
import com.neop2p.domain.model.Escrow
import org.bitcoinj.base.Coin
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.base.SegwitAddress
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.crypto.internal.CryptoUtils
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression (2026-09-24): a V1 (`MULTISIG_2OF3_CLTV_V1`) escrow release is a
 * spend of the `OP_ELSE` branch of an `OP_IF <locktime> OP_CLTV … OP_ELSE …
 * OP_ENDIF` script. That branch needs an explicit FALSE selector pushed AFTER
 * the signatures, otherwise `OP_IF` pops the last signature (truthy), takes the
 * seller-CLTV branch and the node rejects the spend. `assemble2of3Spend` used
 * to build a plain V0 multisig spend for every template, so a V1 release
 * broadcast died with "Broadcast failed on all providers".
 */
class EscrowCltvSpendTest {

    private val net: NetworkParameters = TestNet3Params.get()
    private val arbitratorKey: ECKey =
        ECKey.fromPublicOnly(EscrowCodec.xOnlyToCompressed(NeoP2PConfig.ARBITRATOR_PUBKEY))

    init { Context.getOrCreate(net) }

    private fun payoutTx(): Transaction {
        val tx = Transaction(net)
        tx.addInput(Sha256Hash.wrap("cc".repeat(32)), 0L, ScriptBuilder.createEmpty())
        tx.addOutput(Coin.valueOf(90_000L), LegacyAddress.fromKey(net, ECKey()))
        return tx
    }

    private fun escrow(
        buyerPubHex: String,
        sellerPubHex: String,
        buyerSig: ByteArray,
        fundingAddress: String,
        scriptType: BitcoinAddressType
    ) = Escrow(
        escrowId = "esc-cltv",
        offerId = "off-cltv",
        depositAmountSats = 100_000L,
        tradeAmountSats = 90_000L,
        feeAmountSats = 500L,
        buyerPeerId = "buyer-peer",
        sellerPeerId = "seller-peer",
        fundingAddress = fundingAddress,
        fundingScriptType = scriptType,
        buyerPubKeyHex = buyerPubHex,
        sellerPubKeyHex = sellerPubHex,
        buyerSignature = buyerSig,
        fundedAmountSats = 100_000L
    )

    private fun v1Redeem(buyer: ECKey, seller: ECKey): Script =
        EscrowScripts.build(
            EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1,
            buyer, seller, arbitratorKey, 1_700_000_000L
        )

    @Test
    fun `V1 CLTV P2SH release spend passes consensus script execution`() {
        val buyerKey = ECKey()
        val sellerKey = ECKey()
        val redeem = v1Redeem(buyerKey, sellerKey)
        val p2sh = ScriptBuilder.createP2SHOutputScript(redeem)
        val fundingAddress = LegacyAddress
            .fromScriptHash(net, CryptoUtils.sha256hash160(redeem.getProgram())).toString()

        val tx = payoutTx()
        val buyerSig = EscrowTxBuilder.signRaw(tx, redeem, buyerKey, 100_000L, witness = false)
        val spend = EscrowTxBuilder.assemble2of3Spend(
            tx, redeem,
            escrow(buyerKey.publicKeyAsHex, sellerKey.publicKeyAsHex, buyerSig, fundingAddress, BitcoinAddressType.LEGACY),
            sellerKey.privKeyBytes, null, net
        )
        assertNotNull("two valid sigs must assemble a V1 2-of-3 spend", spend)
        EscrowTxBuilder.attachSpend(tx, spend!!)

        // OP_0 dummy + 2 sigs + OP_0 (OP_ELSE selector) + redeem push.
        assertEquals(5, spend.scriptSig!!.chunks.size)
        // bitcoinj's own verifier, exactly like a node: must accept the spend.
        tx.getInput(0).scriptSig!!.correctlySpends(
            tx, 0, null, null, p2sh, Script.ALL_VERIFY_FLAGS
        )
    }

    @Test
    fun `V1 CLTV P2WSH release spend pushes the OP_ELSE selector`() {
        val buyerKey = ECKey()
        val sellerKey = ECKey()
        val redeem = v1Redeem(buyerKey, sellerKey)
        val fundingAddress = SegwitAddress
            .fromProgram(net, 0, Sha256Hash.hash(redeem.getProgram())).toBech32()

        val tx = payoutTx()
        val buyerSig = EscrowTxBuilder.signRaw(tx, redeem, buyerKey, 100_000L, witness = true)
        val spend = EscrowTxBuilder.assemble2of3Spend(
            tx, redeem,
            escrow(buyerKey.publicKeyAsHex, sellerKey.publicKeyAsHex, buyerSig, fundingAddress, BitcoinAddressType.SEGWIT),
            sellerKey.privKeyBytes, null, net
        )
        assertNotNull(spend)
        val witness = spend!!.witness!!
        // [empty dummy, sig, sig, empty OP_ELSE selector, redeemScript].
        assertEquals(5, witness.pushCount)
        assertEquals(0, witness.getPush(0).size)
        assertEquals(0, witness.getPush(3).size)
        assertTrue(witness.getPush(4).contentEquals(redeem.getProgram()))

        // Execute the redeem script with the witness stack (sans the trailing
        // redeem element) exactly like Bitcoin Core; must not fail.
        val stack = java.util.LinkedList<ByteArray>()
        for (i in 0 until witness.pushCount - 1) stack.add(witness.getPush(i))
        Script.executeScript(tx, 0, redeem, stack, Script.ALL_VERIFY_FLAGS)
    }
}
