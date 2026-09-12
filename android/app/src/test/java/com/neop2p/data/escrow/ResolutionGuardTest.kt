package com.neop2p.data.escrow

import org.bitcoinj.core.*
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionGuardTest {
    private val net: NetworkParameters = TestNet3Params.get()
    private val sellerKey = ECKey()
    private val attackerKey = ECKey()
    private val buyerKey = ECKey()
    private val sellerAddr = LegacyAddress.fromKey(net, sellerKey).toBase58()
    private val attackerAddr = LegacyAddress.fromKey(net, attackerKey).toBase58()
    private val buyerAddr = LegacyAddress.fromKey(net, buyerKey).toBase58()
    private val feeWallet = LegacyAddress.fromKey(net, ECKey()).toBase58()

    private fun tx(vararg outs: Pair<Long, String>): Transaction {
        val t = Transaction(net)
        t.addInput(Sha256Hash.wrap("aa".repeat(32)), 0L, ScriptBuilder.createEmpty())
        outs.forEach { (v, a) -> t.addOutput(Coin.valueOf(v), Address.fromString(net, a)) }
        return t
    }

    private fun refundExpectation() = ResolutionGuard.RefundExpectation(
        destinationAddress = sellerAddr, fundedInputSats = 100_000L, feeCeilingSats = 5_000L
    )

    private fun releaseExpectation() = ResolutionGuard.ReleaseExpectation(
        buyerAddress = buyerAddr, feeWalletAddress = feeWallet,
        sellerRefundAddress = sellerAddr, tradeSats = 90_000L
    )

    @Test fun `refund to attested destination passes`() {
        assertTrue(ResolutionGuard.validateRefund(tx(96_000L to sellerAddr), net, refundExpectation()).ok)
    }

    @Test fun `refund to attacker destination fails`() {
        assertFalse(ResolutionGuard.validateRefund(tx(96_000L to attackerAddr), net, refundExpectation()).ok)
    }

    @Test fun `refund with gouged fee fails`() {
        assertFalse(ResolutionGuard.validateRefund(tx(80_000L to sellerAddr), net, refundExpectation()).ok)
    }

    @Test fun `release to buyer + fee wallet passes`() {
        assertTrue(ResolutionGuard.validateRelease(tx(90_000L to buyerAddr, 500L to feeWallet), net, releaseExpectation()).ok)
    }

    @Test fun `release paying the seller instead of the buyer fails`() {
        assertFalse(ResolutionGuard.validateRelease(tx(90_000L to sellerAddr, 500L to feeWallet), net, releaseExpectation()).ok)
    }

    @Test fun `release missing buyer output fails`() {
        assertFalse(ResolutionGuard.validateRelease(tx(500L to feeWallet, 90_000L to sellerAddr), net, releaseExpectation()).ok)
    }

    @Test fun `release with overpayment excess to seller passes`() {
        assertTrue(ResolutionGuard.validateRelease(tx(90_000L to buyerAddr, 500L to feeWallet, 9_000L to sellerAddr), net, releaseExpectation()).ok)
    }

    @Test fun `output summaries list address and sats`() {
        val s = ResolutionGuard.outputSummaries(tx(90_000L to buyerAddr), net)
        assertTrue(s.single().contains(buyerAddr))
        assertTrue(s.single().contains("90000"))
    }

    // ── F2 anchored destination checks ──

    private val arbKey = ECKey()
    private val redeemHex = ScriptBuilder.createRedeemScript(2, listOf(buyerKey, sellerKey, arbKey)).program.toHex()

    private fun buyerAttestation(fromKey: ECKey, address: String) = RoleAddressAttestation.sign(
        fromKey.privateKeyAsHex, RoleAddressAttestation.KIND_BUYER_PAYOUT, "offer_1", address
    )

    private fun sellerAttestation(fromKey: ECKey, address: String) = RoleAddressAttestation.sign(
        fromKey.privateKeyAsHex, RoleAddressAttestation.KIND_SELLER_REFUND, "escrow_1", address
    )

    @Test fun `anchored buyer payout destination passes`() {
        val att = buyerAttestation(buyerKey, buyerAddr)
        assertTrue(
            ResolutionGuard.verifyBuyerPayoutDestination(
                buyerAddr, buyerKey.publicKeyAsHex, att, "offer_1", redeemHex
            ).ok
        )
    }

    @Test fun `buyer payout signed by an unanchored key fails`() {
        // Valid ECDSA by the attacker's own key — but that key is not in the
        // escrow script, so the on-chain anchor must reject it.
        val att = buyerAttestation(attackerKey, attackerAddr)
        assertFalse(
            ResolutionGuard.verifyBuyerPayoutDestination(
                attackerAddr, attackerKey.publicKeyAsHex, att, "offer_1", redeemHex
            ).ok
        )
    }

    @Test fun `buyer payout with a forged address fails`() {
        val att = buyerAttestation(buyerKey, buyerAddr)
        assertFalse(
            ResolutionGuard.verifyBuyerPayoutDestination(
                attackerAddr, buyerKey.publicKeyAsHex, att, "offer_1", redeemHex
            ).ok
        )
    }

    @Test fun `buyer payout with no attestation or script fails closed`() {
        assertFalse(
            ResolutionGuard.verifyBuyerPayoutDestination(
                buyerAddr, buyerKey.publicKeyAsHex, null, "offer_1", redeemHex
            ).ok
        )
        assertFalse(
            ResolutionGuard.verifyBuyerPayoutDestination(
                buyerAddr, buyerKey.publicKeyAsHex, buyerAttestation(buyerKey, buyerAddr), "offer_1", null
            ).ok
        )
    }

    @Test fun `anchored seller refund destination passes`() {
        val att = sellerAttestation(sellerKey, sellerAddr)
        assertTrue(
            ResolutionGuard.verifySellerRefundDestination(
                sellerAddr, sellerKey.publicKeyAsHex, att, "escrow_1", redeemHex
            ).ok
        )
    }

    @Test fun `seller refund signed by an unanchored key fails`() {
        val att = sellerAttestation(attackerKey, attackerAddr)
        assertFalse(
            ResolutionGuard.verifySellerRefundDestination(
                attackerAddr, attackerKey.publicKeyAsHex, att, "escrow_1", redeemHex
            ).ok
        )
    }
}
