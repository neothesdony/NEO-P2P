package com.neop2p.data.escrow

import com.neop2p.NeoP2PConfig
import com.neop2p.domain.model.BitcoinAddressType
import com.neop2p.domain.model.Escrow
import org.bitcoinj.base.Coin
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.base.SegwitAddress
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the 2-of-3 spend assembly extracted from
 * `EscrowService` (Phase 0b). Unlike `EscrowArbitrationResolutionTest` (which
 * replicates the assembly), this drives the real [EscrowTxBuilder].
 *
 * The configured arbitrator pubkey's private key is not in the repo, so the
 * arbitrator slot is exercised as a redeem-script participant while the two
 * signatures come from the buyer (stored) and the local device key — the same
 * mechanism the parser uses in production.
 */
class EscrowTxBuilderTest {

    private val net: NetworkParameters = TestNet3Params.get()
    private val arbitratorKey: ECKey =
        ECKey.fromPublicOnly(EscrowCodec.xOnlyToCompressed(NeoP2PConfig.ARBITRATOR_PUBKEY))

    private fun redeem(vararg keys: ECKey): Script = ScriptBuilder.createRedeemScript(2, keys.toList())

    private fun payoutTx(): Transaction {
        val tx = Transaction(net)
        tx.addInput(Sha256Hash.wrap("cc".repeat(32)), 0L, ScriptBuilder.createEmpty())
        tx.addOutput(Coin.valueOf(90_000L), LegacyAddress.fromKey(net, ECKey()))
        return tx
    }

    private fun escrow(
        buyerPubHex: String?,
        sellerPubHex: String?,
        buyerSig: ByteArray? = null,
        sellerSig: ByteArray? = null,
        fundingAddress: String? = null,
        scriptType: BitcoinAddressType = BitcoinAddressType.LEGACY,
        fundedSats: Long? = null
    ) = Escrow(
        escrowId = "esc-test",
        offerId = "off-test",
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
        sellerSignature = sellerSig,
        fundedAmountSats = fundedSats
    )

    @Test
    fun `P2SH spend uses a stored buyer sig plus the local seller key`() {
        val buyerKey = ECKey()
        val localKey = ECKey()
        val script = redeem(buyerKey, localKey, arbitratorKey)
        val tx = payoutTx()
        val buyerSig = EscrowTxBuilder.signRaw(tx, script, buyerKey, 100_000L, witness = false)

        val spend = EscrowTxBuilder.assemble2of3Spend(
            tx, script,
            escrow(buyerKey.publicKeyAsHex, localKey.publicKeyAsHex, buyerSig = buyerSig),
            localKey.privateKeyAsHex, null, net
        )

        assertNotNull("two valid signatures must form a 2-of-3 spend", spend)
        assertNotNull(spend!!.scriptSig)
        assertNull(spend.witness)
    }

    @Test
    fun `P2WSH spend uses the witness and leaves the scriptSig empty`() {
        val buyerKey = ECKey()
        val localKey = ECKey()
        val script = redeem(buyerKey, localKey, arbitratorKey)
        val tx = payoutTx()
        val buyerSig = EscrowTxBuilder.signRaw(tx, script, buyerKey, 100_000L, witness = true)
        val bech32 = SegwitAddress.fromKey(net, ECKey()).toString()

        val spend = EscrowTxBuilder.assemble2of3Spend(
            tx, script,
            escrow(
                buyerKey.publicKeyAsHex, localKey.publicKeyAsHex,
                buyerSig = buyerSig, fundingAddress = bech32,
                scriptType = BitcoinAddressType.SEGWIT, fundedSats = 100_000L
            ),
            localKey.privateKeyAsHex, null, net
        )

        assertNotNull(spend)
        assertNotNull(spend!!.witness)
        assertNull(spend.scriptSig)
    }

    @Test
    fun `a single valid signature cannot form a 2-of-3 spend`() {
        val buyerKey = ECKey()
        val localKey = ECKey()
        val strangerKey = ECKey()
        val script = redeem(buyerKey, strangerKey, arbitratorKey)
        val tx = payoutTx()
        val buyerSig = EscrowTxBuilder.signRaw(tx, script, buyerKey, 100_000L, witness = false)

        // Stored buyer sig only; the local key matches neither role.
        val spend = EscrowTxBuilder.assemble2of3Spend(
            tx, script,
            escrow(buyerKey.publicKeyAsHex, strangerKey.publicKeyAsHex, buyerSig = buyerSig),
            localKey.privateKeyAsHex, null, net
        )

        assertNull(spend)
    }

    @Test
    fun `signatures are emitted in ascending redeem-script pubkey order`() {
        val buyerKey = ECKey()
        val localKey = ECKey()
        val script = redeem(buyerKey, localKey, arbitratorKey)
        val tx = payoutTx()
        val buyerSig = EscrowTxBuilder.signRaw(tx, script, buyerKey, 100_000L, witness = false)
        val localSig = EscrowTxBuilder.signRaw(tx, script, localKey, 100_000L, witness = false)

        val spend = EscrowTxBuilder.assemble2of3Spend(
            tx, script,
            escrow(buyerKey.publicKeyAsHex, localKey.publicKeyAsHex, buyerSig = buyerSig),
            localKey.privateKeyAsHex, null, net
        )!!

        val expected = listOf(buyerKey to buyerSig, localKey to localSig)
            .sortedWith { a, b -> EscrowCodec.compareBytes(a.first.pubKey, b.first.pubKey) }
            .map { it.second }
        val chunks = spend.scriptSig!!.chunks
        assertEquals("OP_0 + 2 sig pushes + redeem push", 4, chunks.size)
        assertArrayEquals(expected[0], chunks[1].data)
        assertArrayEquals(expected[1], chunks[2].data)
    }

    @Test
    fun `escrowScriptType derives the type from the address and falls back to the stored field`() {
        val bech32 = SegwitAddress.fromKey(net, ECKey()).toString()
        assertEquals(BitcoinAddressType.SEGWIT, EscrowTxBuilder.escrowScriptType(bech32, "LEGACY", net))
        assertEquals(BitcoinAddressType.SEGWIT, EscrowTxBuilder.escrowScriptType(null, "SEGWIT", net))
        assertEquals(BitcoinAddressType.LEGACY, EscrowTxBuilder.escrowScriptType(null, "bogus", net))
        assertEquals(BitcoinAddressType.LEGACY, EscrowTxBuilder.escrowScriptType(null, null, net))
    }

    @Test
    fun `pubkey matches compressed and x-only forms`() {
        val key = ECKey()
        assertTrue(EscrowTxBuilder.pubkey(key, key.publicKeyAsHex))
        assertTrue(EscrowTxBuilder.pubkey(key, EscrowCodec.xOnlyOf(key.publicKeyAsHex)))
        assertFalse(EscrowTxBuilder.pubkey(key, ECKey().publicKeyAsHex))
    }
}
