package com.neop2p.data.escrow

import com.neop2p.NeoP2PConfig
import com.neop2p.data.p2p.DisputeRecord
import com.neop2p.domain.model.ResolutionDecision
import org.bitcoinj.base.*
import org.bitcoinj.core.*
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.script.ScriptBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArbitrationResolutionTest {

    private val net: NetworkParameters = NetworkParams.current()
    private val sellerKey = ECKey()
    private val buyerKey = ECKey()
    private val arbKey = ECKey()
    private val strangerKey = ECKey()

    private val sellerAddr = LegacyAddress.fromKey(net, sellerKey).toBase58()
    private val buyerAddr = LegacyAddress.fromKey(net, buyerKey).toBase58()
    private val feeWalletAddr = NeoP2PConfig.FEE_WALLET_ADDRESS

    private val redeemHex = ScriptBuilder.createRedeemScript(2, listOf(buyerKey, sellerKey, arbKey)).program.toHex()
    private val escrowId = "escrow_1"
    private val offerId = "offer_1"

    private val sellerAttestation = RoleAddressAttestation.sign(
        sellerKey.privateKeyAsHex, RoleAddressAttestation.KIND_SELLER_REFUND, escrowId, sellerAddr
    )
    private val buyerAttestation = RoleAddressAttestation.sign(
        buyerKey.privateKeyAsHex, RoleAddressAttestation.KIND_BUYER_PAYOUT, offerId, buyerAddr
    )

    @Before
    fun setUp() {
        NeoP2PConfig.network = "mainnet"
    }

    @After
    fun tearDown() {
        NeoP2PConfig.network = NeoP2PConfig.DEFAULT_NETWORK
    }

    private fun tx(vararg outs: Pair<Long, String>): Transaction {
        val t = Transaction(net)
        t.addInput(Sha256Hash.wrap("aa".repeat(32)), 0L, ScriptBuilder.createEmpty())
        outs.forEach { (v, a) -> t.addOutput(Coin.valueOf(v), Address.fromString(net, a)) }
        return t
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun record(
        escrowId: String = this.escrowId,
        psbtHex: String? = null,
        refundTxHex: String? = null,
        redeemScriptHex: String? = redeemHex,
        depositSats: Long? = 100_000L,
        fundingScriptType: String? = null,
        sellerRefundAddress: String? = sellerAddr,
        buyerPeerId: String? = "buyer-peer",
        sellerPeerId: String? = "seller-peer",
        buyerBtcAddress: String? = buyerAddr,
        buyerPubkeyHex: String? = buyerKey.publicKeyAsHex,
        sellerPubkeyHex: String? = sellerKey.publicKeyAsHex,
        sellerRefundAttestation: String? = sellerAttestation,
        buyerAddressAttestation: String? = buyerAttestation,
        offerId: String? = this.offerId,
        tradeSats: Long? = 90_000L,
    ) = DisputeRecord(
        escrowId = escrowId, openedBy = "opener", reason = "r", openedAt = 0L,
        redeemScriptHex = redeemScriptHex, psbtHex = psbtHex, refundTxHex = refundTxHex,
        depositSats = depositSats, fundingScriptType = fundingScriptType,
        sellerRefundAddress = sellerRefundAddress, buyerPeerId = buyerPeerId,
        sellerPeerId = sellerPeerId, buyerBtcAddress = buyerBtcAddress,
        buyerPubkeyHex = buyerPubkeyHex, sellerPubkeyHex = sellerPubkeyHex,
        sellerRefundAttestation = sellerRefundAttestation,
        buyerAddressAttestation = buyerAddressAttestation, offerId = offerId,
        tradeSats = tradeSats, receivedAt = 0L,
    )

    // ── tx selection ──

    @Test fun `release decision signs the payout tx`() {
        val payout = hex(tx(90_000L to buyerAddr).bitcoinSerialize())
        val r = ArbitrationResolution.txToSign(record(psbtHex = payout), ResolutionDecision.RELEASE_TO_BUYER)
        assertEquals(payout, r.getOrThrow())
    }

    @Test fun `refund decision signs the refund tx`() {
        val refund = hex(tx(96_000L to sellerAddr).bitcoinSerialize())
        val r = ArbitrationResolution.txToSign(record(refundTxHex = refund), ResolutionDecision.REFUND_TO_SELLER)
        assertEquals(refund, r.getOrThrow())
    }

    @Test fun `release without a payout tx fails`() {
        val r = ArbitrationResolution.txToSign(record(psbtHex = null), ResolutionDecision.RELEASE_TO_BUYER)
        assertEquals("No unsigned payout tx in dispute", r.exceptionOrNull()?.message)
    }

    @Test fun `refund without a refund tx fails`() {
        val r = ArbitrationResolution.txToSign(record(refundTxHex = null), ResolutionDecision.REFUND_TO_SELLER)
        assertEquals("No unsigned refund tx in dispute — cannot rule a refund", r.exceptionOrNull()?.message)
    }

    @Test fun `refund never falls back to the payout tx`() {
        val payout = hex(tx(90_000L to buyerAddr).bitcoinSerialize())
        val r = ArbitrationResolution.txToSign(
            record(psbtHex = payout, refundTxHex = null), ResolutionDecision.REFUND_TO_SELLER
        )
        assertTrue(r.isFailure)
    }

    // ── refund pre-sign gates ──

    @Test fun `refund to attested seller destination passes`() {
        val refundHex = hex(tx(96_000L to sellerAddr).bitcoinSerialize())
        val v = ArbitrationResolution.preSignVerdict(
            record(refundTxHex = refundHex), ResolutionDecision.REFUND_TO_SELLER, refundHex, net, 100_000L
        )
        assertTrue(v.reason, v.ok)
    }

    @Test fun `refund without an attestation is refused`() {
        val refundHex = hex(tx(96_000L to sellerAddr).bitcoinSerialize())
        val v = ArbitrationResolution.preSignVerdict(
            record(refundTxHex = refundHex, sellerRefundAttestation = null),
            ResolutionDecision.REFUND_TO_SELLER, refundHex, net, 100_000L
        )
        assertFalse(v.ok)
        assertEquals("Refund destination is not attested by the seller key — refusing to sign", v.reason)
    }

    @Test fun `refund to a non-attested destination is blocked`() {
        val strangerAddr = LegacyAddress.fromKey(net, strangerKey).toBase58()
        val refundHex = hex(tx(96_000L to strangerAddr).bitcoinSerialize())
        val v = ArbitrationResolution.preSignVerdict(
            record(refundTxHex = refundHex), ResolutionDecision.REFUND_TO_SELLER, refundHex, net, 100_000L
        )
        assertFalse(v.ok)
        assertTrue(v.reason, v.reason.startsWith("Resolution blocked: "))
    }

    @Test fun `refund with a role key outside the script is refused`() {
        val otherKey = ECKey()
        val otherAtt = RoleAddressAttestation.sign(
            otherKey.privateKeyAsHex, RoleAddressAttestation.KIND_SELLER_REFUND, escrowId, sellerAddr
        )
        val refundHex = hex(tx(96_000L to sellerAddr).bitcoinSerialize())
        val v = ArbitrationResolution.preSignVerdict(
            record(refundTxHex = refundHex, sellerPubkeyHex = otherKey.publicKeyAsHex, sellerRefundAttestation = otherAtt),
            ResolutionDecision.REFUND_TO_SELLER, refundHex, net, 100_000L
        )
        assertFalse(v.ok)
        assertEquals("Role key is not a key of the escrow redeem script — refusing to sign", v.reason)
    }

    // ── release pre-sign gates ──

    @Test fun `release to attested buyer + fee wallet passes`() {
        val payoutHex = hex(tx(90_000L to buyerAddr, 500L to feeWalletAddr).bitcoinSerialize())
        val v = ArbitrationResolution.preSignVerdict(
            record(psbtHex = payoutHex), ResolutionDecision.RELEASE_TO_BUYER, payoutHex, net, 100_000L
        )
        assertTrue(v.reason, v.ok)
    }

    @Test fun `release without an attestation is refused`() {
        val payoutHex = hex(tx(90_000L to buyerAddr).bitcoinSerialize())
        val v = ArbitrationResolution.preSignVerdict(
            record(psbtHex = payoutHex, buyerAddressAttestation = null),
            ResolutionDecision.RELEASE_TO_BUYER, payoutHex, net, 100_000L
        )
        assertFalse(v.ok)
        assertEquals("Payout destination is not attested by the buyer key — refusing to sign", v.reason)
    }

    @Test fun `release to a non-attested destination is blocked`() {
        val strangerAddr = LegacyAddress.fromKey(net, strangerKey).toBase58()
        val payoutHex = hex(tx(90_000L to strangerAddr).bitcoinSerialize())
        val v = ArbitrationResolution.preSignVerdict(
            record(psbtHex = payoutHex), ResolutionDecision.RELEASE_TO_BUYER, payoutHex, net, 100_000L
        )
        assertFalse(v.ok)
        assertTrue(v.reason, v.reason.startsWith("Resolution blocked: "))
    }

    @Test fun `release with a role key outside the script is refused`() {
        val otherKey = ECKey()
        val otherAtt = RoleAddressAttestation.sign(
            otherKey.privateKeyAsHex, RoleAddressAttestation.KIND_BUYER_PAYOUT, offerId, buyerAddr
        )
        val payoutHex = hex(tx(90_000L to buyerAddr).bitcoinSerialize())
        val v = ArbitrationResolution.preSignVerdict(
            record(psbtHex = payoutHex, buyerPubkeyHex = otherKey.publicKeyAsHex, buyerAddressAttestation = otherAtt),
            ResolutionDecision.RELEASE_TO_BUYER, payoutHex, net, 100_000L
        )
        assertFalse(v.ok)
        assertEquals("Role key is not a key of the escrow redeem script — refusing to sign", v.reason)
    }

    @Test fun `missing redeem script is refused`() {
        val refundHex = hex(tx(96_000L to sellerAddr).bitcoinSerialize())
        val v = ArbitrationResolution.preSignVerdict(
            record(refundTxHex = refundHex, redeemScriptHex = null),
            ResolutionDecision.REFUND_TO_SELLER, refundHex, net, 100_000L
        )
        assertFalse(v.ok)
        assertEquals("No redeem script in dispute", v.reason)
    }

    // ── role key anchor ──

    @Test fun `compressed role key matches the redeem script`() {
        assertTrue(ArbitrationResolution.roleKeyInRedeemScript(redeemHex, sellerKey.publicKeyAsHex))
    }

    @Test fun `x-only role key matches the redeem script`() {
        val xOnly = EscrowCodec.xOnlyOf(sellerKey.publicKeyAsHex)
        assertEquals(64, xOnly.length)
        assertTrue(ArbitrationResolution.roleKeyInRedeemScript(redeemHex, xOnly))
    }

    @Test fun `uncompressed role key does not match a compressed script key`() {
        // Faithful port of the app's xOnly(): for a 65B key the last 32 bytes are
        // Y, not X, so it never matches a compressed script key. Fail-closed —
        // documented here so the quirk is not mistaken for a regression.
        val uncompressed = hex(strangerKey.decompress().pubKey)
        assertEquals(130, uncompressed.length)
        assertFalse(ArbitrationResolution.roleKeyInRedeemScript(redeemHex, uncompressed))
    }

    @Test fun `a key absent from the script does not match`() {
        assertFalse(ArbitrationResolution.roleKeyInRedeemScript(redeemHex, strangerKey.publicKeyAsHex))
    }

    @Test fun `unparseable script fails open`() {
        // "01" is a truncated push (1 byte claimed, none present) — parsing throws.
        assertTrue(ArbitrationResolution.roleKeyInRedeemScript("01", sellerKey.publicKeyAsHex))
    }

    @Test fun `role key anchors on V1, parsed keyless fails, unparseable fails open`() {
        val v1 = EscrowScripts.build(
            EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, buyerKey, sellerKey, arbKey, 1_790_000_000L
        )
        assertTrue(ArbitrationResolution.roleKeyInRedeemScript(v1.program.toHex(), sellerKey.publicKeyAsHex))
        // OP_1 parses cleanly but commits no key: a parsed keyless script is not an anchor.
        assertFalse(ArbitrationResolution.roleKeyInRedeemScript("51", sellerKey.publicKeyAsHex))
        assertTrue(ArbitrationResolution.roleKeyInRedeemScript("01", sellerKey.publicKeyAsHex))
    }

    // ── targets / summaries / sign ──

    @Test fun `targets are the dispute parties, order preserved and deduped`() {
        assertEquals(
            listOf("b", "s"),
            ArbitrationResolution.targets(record(buyerPeerId = "b", sellerPeerId = "s"))
        )
        assertEquals(
            listOf("both"),
            ArbitrationResolution.targets(record(buyerPeerId = "both", sellerPeerId = "both"))
        )
    }

    @Test fun `targets drop blanks`() {
        assertTrue(ArbitrationResolution.targets(record(buyerPeerId = null, sellerPeerId = null)).isEmpty())
    }

    @Test fun `output summaries list address and sats`() {
        val payoutHex = hex(tx(90_000L to buyerAddr).bitcoinSerialize())
        val s = ArbitrationResolution.outputSummaries(payoutHex, net)
        assertTrue(s.single().contains(buyerAddr))
        assertTrue(s.single().contains("90000"))
    }

    @Test fun `sign rejects a non-arbitrator key`() {
        val payoutHex = hex(tx(90_000L to buyerAddr).bitcoinSerialize())
        val r = ArbitrationResolution.sign(record(psbtHex = payoutHex), payoutHex, strangerKey.privateKeyAsHex)
        assertTrue(r.isFailure)
    }

    @Test fun `sign refuses without a redeem script`() {
        val payoutHex = hex(tx(90_000L to buyerAddr).bitcoinSerialize())
        val r = ArbitrationResolution.sign(record(psbtHex = payoutHex, redeemScriptHex = null), payoutHex, strangerKey.privateKeyAsHex)
        assertEquals("No redeem script in dispute", r.exceptionOrNull()?.message)
    }
}
