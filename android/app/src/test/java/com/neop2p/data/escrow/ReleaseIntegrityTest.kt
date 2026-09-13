package com.neop2p.data.escrow

import com.neop2p.NeoP2PConfig
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-3 (2026-09-13): the single pre-broadcast payout verdict. The stored
 * `psbt_unsigned` is untrustworthy at broadcast time (a peer can overwrite it
 * over `escrow_status`), so the destination must be re-derived from the local
 * attested values on every device before the tx is broadcast.
 */
class ReleaseIntegrityTest {
    private val net = TestNet3Params.get()
    private val buyer = ECKey()
    private val seller = ECKey()
    private val arb = ECKey()
    private val redeem = ScriptBuilder.createRedeemScript(2, listOf(buyer, seller, arb))
    private val buyerAddr = LegacyAddress.fromKey(net, buyer).toBase58()
    private val sellerAddr = LegacyAddress.fromKey(net, seller).toBase58()
    // Network-explicit (the debug build may be mainnet): these txs are built
    // with TestNet3Params, so the fee wallet must be the testnet trio address.
    private val feeWallet = NeoP2PConfig.FEE_WALLET_ADDRESS_TESTNET
    private val offerId = "offer-1"
    private val attestation = RoleAddressAttestation.sign(
        privateKeyHex = buyer.privateKeyAsHex,
        kind = RoleAddressAttestation.KIND_BUYER_PAYOUT,
        scopeId = offerId,
        address = buyerAddr
    )

    private fun Script.programHex(): String = program.joinToString("") { "%02x".format(it) }

    private fun args(tx: Transaction, attest: String? = attestation, script: String? = redeem.programHex()) =
        ReleaseIntegrity.Arguments(
            buyerBtcAddress = buyerAddr,
            buyerPubkeyHex = buyer.publicKeyAsHex,
            buyerAddressAttestation = attest,
            offerId = offerId,
            redeemScriptHex = script,
            feeWalletAddress = feeWallet,
            sellerRefundAddress = sellerAddr,
            tradeSats = 100_000L,
            tx = tx,
            net = net
        )

    private fun tx(paying: List<Pair<String, Long>>): Transaction {
        val t = Transaction(net)
        t.addInput(Sha256Hash.wrap("11".repeat(32)), 0L, ScriptBuilder.createEmpty())
        paying.forEach { (addr, sats) ->
            t.addOutput(Coin.valueOf(sats), Address.fromString(net, addr))
        }
        return t
    }

    @Test
    fun `honest payout to buyer and fee wallet passes`() {
        val t = tx(listOf(buyerAddr to 100_000L, feeWallet to 546L))
        assertTrue(ReleaseIntegrity.verdict(args(t)).ok)
    }

    @Test
    fun `payout to a stranger is refused`() {
        val stranger = LegacyAddress.fromKey(net, ECKey()).toBase58()
        val v = ReleaseIntegrity.verdict(args(tx(listOf(stranger to 100_000L))))
        assertFalse(v.ok)
        assertTrue(v.reason.contains("not an expected destination"))
    }

    @Test
    fun `payout below the trade amount is refused`() {
        assertFalse(ReleaseIntegrity.verdict(args(tx(listOf(buyerAddr to 99_999L)))).ok)
    }

    @Test
    fun `an extra output to a stranger is refused`() {
        val stranger = LegacyAddress.fromKey(net, ECKey()).toBase58()
        val v = ReleaseIntegrity.verdict(args(tx(listOf(buyerAddr to 100_000L, stranger to 1L))))
        assertFalse(v.ok)
    }

    @Test
    fun `a missing attestation fails closed`() {
        val v = ReleaseIntegrity.verdict(args(tx(listOf(buyerAddr to 100_000L)), attest = null))
        assertFalse(v.ok)
        assertTrue(v.reason.contains("not attested"))
    }

    @Test
    fun `a redeem script without the buyer key is refused`() {
        val other = ScriptBuilder.createRedeemScript(2, listOf(seller, arb, ECKey()))
        val v = ReleaseIntegrity.verdict(
            args(tx(listOf(buyerAddr to 100_000L)), script = other.programHex())
        )
        assertFalse(v.ok)
        assertTrue(v.reason.contains("not anchored"))
    }
}
