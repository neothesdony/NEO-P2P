package com.neop2p.admind

import com.neop2p.NeoP2PConfig
import com.neop2p.data.escrow.NetworkParams
import com.neop2p.data.escrow.RoleAddressAttestation
import com.neop2p.data.p2p.DisputeRecord
import com.neop2p.data.p2p.DisputeStore
import com.neop2p.data.p2p.PendingResolution
import com.neop2p.data.p2p.ResolutionSender
import com.neop2p.data.p2p.ResolutionStore
import com.neop2p.domain.model.ResolutionDecision
import kotlinx.coroutines.runBlocking
import org.bitcoinj.base.Address
import org.bitcoinj.base.Coin
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.script.ScriptBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Refusal + orchestration coverage for `admind resolve`. The positive signing
 * path is unreachable in-JVM (the arbitrator private key is not in the repo),
 * so the sign step is injected; every other gate is exercised for real.
 */
class ResolveCommandTest {

    private val net = NetworkParams.current()
    private val sellerKey = ECKey()
    private val buyerKey = ECKey()
    private val arbKey = ECKey()
    private val strangerKey = ECKey()
    private val sellerAddr = LegacyAddress.fromKey(net, sellerKey).toBase58()
    private val buyerAddr = LegacyAddress.fromKey(net, buyerKey).toBase58()
    private val feeWallet = NeoP2PConfig.FEE_WALLET_ADDRESS
    private val redeemHex = hex(ScriptBuilder.createRedeemScript(2, listOf(buyerKey, sellerKey, arbKey)).program)
    private val escrowId = "esc-1"
    private val offerId = "off-1"
    private val sellerAtt = RoleAddressAttestation.sign(
        sellerKey.privateKeyAsHex, RoleAddressAttestation.KIND_SELLER_REFUND, escrowId, sellerAddr
    )
    private val buyerAtt = RoleAddressAttestation.sign(
        buyerKey.privateKeyAsHex, RoleAddressAttestation.KIND_BUYER_PAYOUT, offerId, buyerAddr
    )

    @Before fun setUp() { NeoP2PConfig.network = "mainnet" }
    @After fun tearDown() { NeoP2PConfig.network = NeoP2PConfig.DEFAULT_NETWORK }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun payoutHex(): String {
        val t = Transaction(net)
        t.addInput(Sha256Hash.wrap("aa".repeat(32)), 0L, ScriptBuilder.createEmpty())
        t.addOutput(Coin.valueOf(90_000L), Address.fromString(net, buyerAddr))
        t.addOutput(Coin.valueOf(500L), Address.fromString(net, feeWallet))
        return hex(t.bitcoinSerialize())
    }

    private fun record(
        psbtHex: String? = payoutHex(),
        buyerAddressAttestation: String? = buyerAtt,
        buyerPeerId: String? = "buyer-peer",
        sellerPeerId: String? = "seller-peer",
    ) = DisputeRecord(
        escrowId = escrowId, openedBy = "buyer", reason = "r", openedAt = 0L,
        redeemScriptHex = redeemHex, psbtHex = psbtHex, refundTxHex = null,
        depositSats = 100_000L, fundingScriptType = null, sellerRefundAddress = sellerAddr,
        buyerPeerId = buyerPeerId, sellerPeerId = sellerPeerId,
        buyerBtcAddress = buyerAddr, buyerPubkeyHex = buyerKey.publicKeyAsHex,
        sellerPubkeyHex = sellerKey.publicKeyAsHex, sellerRefundAttestation = sellerAtt,
        buyerAddressAttestation = buyerAddressAttestation, offerId = offerId,
        tradeSats = 90_000L, receivedAt = 0L,
    )

    private class FakeDisputes(private val rows: MutableMap<String, DisputeRecord>) : DisputeStore {
        val resolvedIds = mutableListOf<String>()
        override fun getById(escrowId: String): DisputeRecord? = rows[escrowId]
        override fun countUnresolvedBySender(openedBy: String): Int =
            rows.values.count { it.openedBy == openedBy && !it.resolved }
        override fun upsert(record: DisputeRecord) { rows[record.escrowId] = record }
        override fun markResolved(escrowId: String) { resolvedIds.add(escrowId) }
        override fun all(): List<DisputeRecord> = rows.values.toList()
        override fun clear() { rows.clear() }
    }

    private class FakeResolutions : ResolutionStore {
        val rows = linkedMapOf<String, PendingResolution>()
        override fun save(resolution: PendingResolution) { rows[resolution.escrowId] = resolution }
        override fun load(escrowId: String): PendingResolution? = rows[escrowId]
        override fun all(): List<PendingResolution> = rows.values.toList()
        override fun remove(escrowId: String) { rows.remove(escrowId) }
        override fun clear() { rows.clear() }
    }

    private class FakeSender : ResolutionSender {
        val sent = mutableListOf<String>()
        var failing: Set<String> = emptySet()
        override suspend fun sendResolution(
            toPeerId: String,
            escrowId: String,
            decision: String,
            arbitratorSigHex: String,
            notes: String?,
            sellerRefundAddress: String?,
            signedTxHex: String?,
        ): Boolean {
            sent.add(toPeerId)
            return toPeerId !in failing
        }
    }

    private class Harness(record: DisputeRecord?) {
        val disputes = FakeDisputes(mutableMapOf<String, DisputeRecord>().also { if (record != null) it["esc-1"] = record })
        val resolutions = FakeResolutions()
        val sender = FakeSender()
        var senderOpened = false
        var keyDerived = false
        val lines = mutableListOf<String>()
    }

    private fun run(
        h: Harness,
        confirm: Boolean,
        decision: ResolutionDecision = ResolutionDecision.RELEASE_TO_BUYER,
        keyHex: String = strangerKey.privateKeyAsHex,
        sign: (DisputeRecord, String, String) -> Result<String> = { r, tx, k ->
            com.neop2p.data.escrow.ArbitrationResolution.sign(r, tx, k)
        },
    ): Int = runBlocking {
        ResolveCommand.run(
            disputes = h.disputes,
            resolutions = h.resolutions,
            escrowId = "esc-1",
            decision = decision,
            notes = "paid",
            confirm = confirm,
            arbitratorPrivKeyHex = { h.keyDerived = true; keyHex },
            senderProvider = { h.senderOpened = true; h.sender },
            net = net,
            sign = sign,
            out = { h.lines.add(it) },
        )
    }

    @Test fun `unknown escrow is refused`() {
        val h = Harness(null)
        assertEquals(1, run(h, confirm = false))
        assertFalse(h.senderOpened)
        assertTrue(h.lines.any { it.contains("No dispute stored") })
    }

    @Test fun `dry run prints destinations without deriving a key or opening a session`() {
        val h = Harness(record())
        assertEquals(0, run(h, confirm = false))
        assertTrue(h.lines.any { it.contains(buyerAddr) })
        assertTrue(h.lines.any { it.contains("Not signing") })
        assertFalse(h.keyDerived)
        assertFalse(h.senderOpened)
        assertEquals(0, h.resolutions.rows.size)
        assertTrue(h.disputes.resolvedIds.isEmpty())
    }

    @Test fun `confirm without a payout tx is refused before signing`() {
        val h = Harness(record(psbtHex = null))
        assertEquals(1, run(h, confirm = true))
        assertTrue(h.lines.any { it.contains("No unsigned payout tx in dispute") })
        assertFalse(h.keyDerived)
        assertFalse(h.senderOpened)
    }

    @Test fun `confirm on a legacy dispute with no attestation fails closed`() {
        val h = Harness(record(buyerAddressAttestation = null))
        assertEquals(1, run(h, confirm = true))
        assertTrue(h.lines.any { it.contains("not attested by the buyer key") })
        assertFalse(h.keyDerived)
        assertFalse(h.senderOpened)
    }

    @Test fun `confirm without targets is refused`() {
        val h = Harness(record(buyerPeerId = null, sellerPeerId = null))
        assertEquals(1, run(h, confirm = true))
        assertTrue(h.lines.any { it.contains("No resolution targets") })
        assertFalse(h.senderOpened)
    }

    @Test fun `a non-arbitrator key is refused before opening a session`() {
        val h = Harness(record())
        assertEquals(1, run(h, confirm = true))
        assertTrue(h.lines.any { it.contains("Refusing") })
        assertTrue(h.keyDerived)
        assertFalse(h.senderOpened)
        assertTrue(h.disputes.resolvedIds.isEmpty())
    }

    @Test fun `full delivery marks the dispute resolved`() {
        val h = Harness(record())
        val exit = run(h, confirm = true, sign = { _, _, _ -> Result.success("sig") })
        assertEquals(0, exit)
        assertEquals(listOf("buyer-peer", "seller-peer"), h.sender.sent)
        assertEquals(listOf("esc-1"), h.disputes.resolvedIds)
        assertEquals(0, h.resolutions.rows.size)
    }

    @Test fun `partial delivery persists the failed target and does not mark resolved`() {
        val h = Harness(record())
        h.sender.failing = setOf("seller-peer")
        val exit = run(h, confirm = true, sign = { _, _, _ -> Result.success("sig") })
        assertEquals(1, exit)
        assertEquals(listOf("seller-peer"), h.resolutions.rows["esc-1"]?.targets)
        assertTrue(h.disputes.resolvedIds.isEmpty())
        assertTrue(h.lines.any { it.contains("auto-retry") })
    }
}
