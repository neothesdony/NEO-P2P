package com.neop2p.admind.web

import com.neop2p.NeoP2PConfig
import com.neop2p.data.escrow.NetworkParams
import com.neop2p.data.escrow.RoleAddressAttestation
import com.neop2p.data.p2p.DisputeRecord
import com.neop2p.data.p2p.DisputeStore
import com.neop2p.data.p2p.EvidenceRecord
import com.neop2p.data.p2p.EvidenceStore
import com.neop2p.data.p2p.PendingResolution
import com.neop2p.data.p2p.ResolutionSender
import com.neop2p.data.p2p.ResolutionStore
import org.bitcoinj.base.Address
import org.bitcoinj.base.Coin
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.script.ScriptBuilder

/**
 * In-memory ports shared by [ConsoleApiTest] and [ConsoleServerTest] so the
 * HTTP boundary and the rule layer exercise the same fixtures.
 */

internal fun testRecord(
    escrowId: String = "esc-1",
    psbtHex: String? = "payout",
    refundTxHex: String? = null,
    resolved: Boolean = false,
    buyerPeerId: String? = "buyer-peer",
    sellerPeerId: String? = "seller-peer",
    buyerAddressAttestation: String? = "att-b",
) = DisputeRecord(
    escrowId = escrowId, openedBy = "buyer", reason = "not received", openedAt = 11L,
    redeemScriptHex = "00", psbtHex = psbtHex, refundTxHex = refundTxHex,
    depositSats = 100_000L, fundingScriptType = null, sellerRefundAddress = "seller-addr",
    buyerPeerId = buyerPeerId, sellerPeerId = sellerPeerId,
    buyerBtcAddress = "buyer-addr", buyerPubkeyHex = "b", sellerPubkeyHex = "s",
    sellerRefundAttestation = "att-s", buyerAddressAttestation = buyerAddressAttestation,
    offerId = "off-1", tradeSats = 90_000L, receivedAt = 22L, resolved = resolved,
)

/**
 * A fully-attested, parseable dispute (real keys, redeem script, payout tx) so
 * the happy-path plan preview can run against the real F2 gate. Mirrors the
 * fixture in `ResolveCommandTest`; requires `NeoP2PConfig.network = "mainnet"`.
 */
internal class DisputeFixture {
    private val net = NetworkParams.current()
    private val sellerKey = ECKey()
    private val buyerKey = ECKey()
    private val arbKey = ECKey()
    val sellerAddr: String = LegacyAddress.fromKey(net, sellerKey).toBase58()
    val buyerAddr: String = LegacyAddress.fromKey(net, buyerKey).toBase58()
    val escrowId = "esc-1"
    private val offerId = "off-1"
    private val sellerAtt = RoleAddressAttestation.sign(
        sellerKey.privateKeyAsHex, RoleAddressAttestation.KIND_SELLER_REFUND, escrowId, sellerAddr,
    )
    private val buyerAtt = RoleAddressAttestation.sign(
        buyerKey.privateKeyAsHex, RoleAddressAttestation.KIND_BUYER_PAYOUT, offerId, buyerAddr,
    )
    private val redeemHex = hex(ScriptBuilder.createRedeemScript(2, listOf(buyerKey, sellerKey, arbKey)).program)

    fun payoutHex(): String {
        val tx = Transaction(net)
        tx.addInput(Sha256Hash.wrap("aa".repeat(32)), 0L, ScriptBuilder.createEmpty())
        tx.addOutput(Coin.valueOf(90_000L), Address.fromString(net, buyerAddr))
        tx.addOutput(Coin.valueOf(500L), Address.fromString(net, NeoP2PConfig.FEE_WALLET_ADDRESS))
        return hex(tx.bitcoinSerialize())
    }

    fun record(buyerAddressAttestation: String? = buyerAtt) = DisputeRecord(
        escrowId = escrowId, openedBy = "buyer", reason = "not received", openedAt = 11L,
        redeemScriptHex = redeemHex, psbtHex = payoutHex(), refundTxHex = null,
        depositSats = 100_000L, fundingScriptType = null, sellerRefundAddress = sellerAddr,
        buyerPeerId = "buyer-peer", sellerPeerId = "seller-peer",
        buyerBtcAddress = buyerAddr, buyerPubkeyHex = buyerKey.publicKeyAsHex,
        sellerPubkeyHex = sellerKey.publicKeyAsHex, sellerRefundAttestation = sellerAtt,
        buyerAddressAttestation = buyerAddressAttestation, offerId = offerId,
        tradeSats = 90_000L, receivedAt = 22L,
    )

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

internal fun testEvidence(id: String = "ev-1", escrowId: String = "esc-1") = EvidenceRecord(
    evidenceId = id, escrowId = escrowId, submitterPeerId = "buyer-peer",
    description = "bank transfer", mimeType = "image/png",
    imageData = byteArrayOf(1, 2, 3, 4), submittedAt = 33L,
)

internal class FakeDisputes(
    val rows: MutableMap<String, DisputeRecord> = mutableMapOf(),
) : DisputeStore {
    val resolvedIds = mutableListOf<String>()
    override fun getById(escrowId: String): DisputeRecord? = rows[escrowId]
    override fun countUnresolvedBySender(openedBy: String): Int =
        rows.values.count { it.openedBy == openedBy && !it.resolved }
    override fun upsert(record: DisputeRecord) { rows[record.escrowId] = record }
    override fun markResolved(escrowId: String) {
        resolvedIds.add(escrowId)
        rows[escrowId]?.let { rows[escrowId] = it.copy(resolved = true) }
    }
    override fun all(): List<DisputeRecord> = rows.values.toList()
    override fun clear() { rows.clear() }
}

internal class FakeEvidence(
    private val rows: MutableList<EvidenceRecord> = mutableListOf(),
) : EvidenceStore {
    override fun forEscrow(escrowId: String) = rows.filter { it.escrowId == escrowId }
    override fun insert(record: EvidenceRecord) { rows.add(record) }
    override fun all() = rows.toList()
    override fun clear() { rows.clear() }
}

internal class FakeResolutions : ResolutionStore {
    val rows = linkedMapOf<String, PendingResolution>()
    override fun save(resolution: PendingResolution) { rows[resolution.escrowId] = resolution }
    override fun load(escrowId: String) = rows[escrowId]
    override fun all() = rows.values.toList()
    override fun remove(escrowId: String) { rows.remove(escrowId) }
    override fun clear() { rows.clear() }
}

internal class FakeSender : ResolutionSender {
    val sent = mutableListOf<String>()
    var failing: Set<String> = emptySet()
    override suspend fun sendResolution(
        toPeerId: String, escrowId: String, decision: String, arbitratorSigHex: String,
        notes: String?, sellerRefundAddress: String?, signedTxHex: String?,
    ): Boolean {
        sent.add(toPeerId)
        return toPeerId !in failing
    }
}
