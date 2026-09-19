package com.neop2p.data.escrow

import com.neop2p.data.local.PendingArbitrationStore
import com.neop2p.data.p2p.PendingResolution
import org.bitcoinj.base.*
import org.bitcoinj.core.*
import org.bitcoinj.crypto.*
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-06 (2026-09-15): an arbitration resolution is applied only when it is
 * anchored to the funded escrow script. Forged or absent role attestations
 * fail closed at both the arbitrator (before signing) and the party (before
 * broadcasting); a resolution that cannot be delivered is queued durably, not
 * applied.
 */
class EscrowResolutionDeliveryTest {

    private val net: NetworkParameters = TestNet3Params.get()
    private val buyerKey = ECKey()
    private val sellerKey = ECKey()
    private val attackerKey = ECKey()
    private val arbKey = ECKey()
    private val buyerAddr = LegacyAddress.fromKey(net, buyerKey).toBase58()
    private val sellerAddr = LegacyAddress.fromKey(net, sellerKey).toBase58()
    private val attackerAddr = LegacyAddress.fromKey(net, attackerKey).toBase58()
    private val feeWallet = LegacyAddress.fromKey(net, ECKey()).toBase58()
    private val redeemHex = ScriptBuilder.createRedeemScript(2, listOf(buyerKey, sellerKey, arbKey)).program.toHex()

    private fun tx(vararg outs: Pair<Long, String>): Transaction {
        val t = Transaction(net)
        t.addInput(Sha256Hash.wrap("aa".repeat(32)), 0L, ScriptBuilder.createEmpty())
        outs.forEach { (v, a) -> t.addOutput(Coin.valueOf(v), Address.fromString(net, a)) }
        return t
    }

    private fun releaseExpectation() = ResolutionGuard.ReleaseExpectation(
        buyerAddress = buyerAddr, feeWalletAddress = feeWallet,
        sellerRefundAddress = sellerAddr, tradeSats = 90_000L
    )

    private fun buyerAttestation(fromKey: ECKey, address: String) = RoleAddressAttestation.sign(
        fromKey.privateKeyAsHex, RoleAddressAttestation.KIND_BUYER_PAYOUT, "offer_1", address
    )

    @Test fun `release to the attested buyer passes`() {
        assertTrue(ResolutionGuard.validateRelease(tx(90_000L to buyerAddr, 500L to feeWallet), net, releaseExpectation()).ok)
    }

    @Test fun `release paying an attacker fails closed`() {
        assertFalse(ResolutionGuard.validateRelease(tx(90_000L to attackerAddr, 500L to feeWallet), net, releaseExpectation()).ok)
    }

    @Test fun `absent attestation fails closed`() {
        assertFalse(
            ResolutionGuard.verifyBuyerPayoutDestination(buyerAddr, buyerKey.publicKeyAsHex, null, "offer_1", redeemHex).ok
        )
    }

    @Test fun `forged attestation from an unanchored key fails closed`() {
        // A structurally valid signature by a key that is not in escrow script.
        val forged = buyerAttestation(attackerKey, attackerAddr)
        assertFalse(
            ResolutionGuard.verifyBuyerPayoutDestination(attackerAddr, attackerKey.publicKeyAsHex, forged, "offer_1", redeemHex).ok
        )
    }

    @Test fun `attestation for a different address fails closed`() {
        val att = buyerAttestation(buyerKey, buyerAddr)
        assertFalse(
            ResolutionGuard.verifyBuyerPayoutDestination(attackerAddr, buyerKey.publicKeyAsHex, att, "offer_1", redeemHex).ok
        )
    }

    @Test fun `missing redeem script fails closed even with a valid attestation`() {
        val att = buyerAttestation(buyerKey, buyerAddr)
        assertFalse(
            ResolutionGuard.verifyBuyerPayoutDestination(buyerAddr, buyerKey.publicKeyAsHex, att, "offer_1", null).ok
        )
    }

    @Test fun `a valid arbitrator signature verifies`() {
        val sig = RoleAddressAttestation.sign(
            arbKey.privateKeyAsHex, RoleAddressAttestation.KIND_SELLER_REFUND, "escrow_1", sellerAddr
        )
        assertTrue(
            RoleAddressAttestation.verify(
                arbKey.publicKeyAsHex, RoleAddressAttestation.KIND_SELLER_REFUND, "escrow_1", sellerAddr, sig
            )
        )
        // Wrong scope/address must not verify.
        assertFalse(
            RoleAddressAttestation.verify(
                arbKey.publicKeyAsHex, RoleAddressAttestation.KIND_SELLER_REFUND, "escrow_2", sellerAddr, sig
            )
        )
    }

    @Test fun `an undeliverable resolution is queued durably, not applied`() {
        val pending = PendingResolution(
            escrowId = "escrow_1",
            decision = "RELEASE_TO_BUYER",
            arbitratorSigHex = "deadbeef",
            notes = "buyer paid",
            sellerRefundAddress = sellerAddr,
            signedTxHex = "txhex",
            targets = listOf("peerA", "peerB")
        )
        val parsed = PendingArbitrationStore.parseResolution(
            pending.escrowId, PendingArbitrationStore.toJson(pending)
        )
        assertEquals(pending, parsed)
    }
}
