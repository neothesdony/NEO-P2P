package com.neop2p.data.p2p

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Wire payload of an LXMF `dispute` message. */
data class InboundDispute(
    val escrowId: String,
    val openedBy: String,
    val reason: String,
    val openedAt: Long,
    val redeemScriptHex: String?,
    val psbtHex: String?,
    val refundTxHex: String?,
    val depositSats: Long?,
    val fundingScriptType: String?,
    val sellerRefundAddress: String?,
    val buyerPeerId: String?,
    val sellerPeerId: String?,
    val buyerBtcAddress: String?,
    val buyerPubkeyHex: String?,
    val sellerPubkeyHex: String?,
    val sellerRefundAttestation: String?,
    val buyerAddressAttestation: String?,
    val offerId: String?,
    val tradeSats: Long?,
    val fundingTxid: String? = null,
    val fundingVout: Int? = null,
    val scriptTemplate: String? = null,
    val cltvLocktime: Long? = null,
)

/** Wire payload of an LXMF `evidence` message. */
data class InboundEvidence(
    val escrowId: String,
    val submitter: String,
    val description: String,
    val mimeType: String,
    val imageBase64: String,
)

sealed interface DisputeIngestDecision {
    /** [record] is the fully-merged row to persist (F2 preserved, resolved=false). */
    data class Accept(val record: DisputeRecord, val isNew: Boolean) : DisputeIngestDecision

    /**
     * Drop, with a log-safe [reason] (never the raw payload). [warn] mirrors the
     * host's log level: a resolved re-delivery is expected noise (`Log.d`) while
     * every other rejection is a warning (`Log.w`).
     */
    data class Drop(val reason: String, val warn: Boolean = true) : DisputeIngestDecision
}

sealed interface EvidenceIngestDecision {
    /** [imageData] is decoded, or null when the payload carried no usable image. */
    data class Accept(
        val escrowId: String,
        val submitter: String,
        val description: String,
        val mimeType: String,
        val imageData: ByteArray?,
    ) : EvidenceIngestDecision
    data class Drop(val reason: String) : EvidenceIngestDecision
}

/**
 * Pure arbitration ingest (F1/F4) extracted from `P2POrchestrator` so `:app`
 * and the headless arbitrator daemon (Phase 1b) share ONE implementation of
 * the parse + guard order. No Android types, no I/O — the host supplies the
 * existing-row snapshot and the per-sender unresolved count.
 */
object ArbitrationIngest {

    const val TYPE_DISPUTE = "dispute"
    const val TYPE_EVIDENCE = "evidence"
    const val TYPE_RESOLUTION = "resolution"

    /** Mirrors P2POrchestrator.MAX_EVIDENCE_BASE64_CHARS (I5 inbound cap). */
    const val MAX_EVIDENCE_BASE64_CHARS: Int = 80 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ── parsing ─────────────────────────────────────────────────────────

    fun parseDispute(raw: String): InboundDispute? =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()?.let(::parseDispute)

    fun parseDispute(obj: JsonObject): InboundDispute? {
        val escrowId = runCatching { obj["escrow_id"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return null
        return InboundDispute(
            escrowId = escrowId,
            openedBy = obj.string("opened_by") ?: "",
            reason = obj.string("reason") ?: "",
            openedAt = obj.long("opened_at") ?: System.currentTimeMillis(),
            redeemScriptHex = obj.string("redeem_script_hex"),
            psbtHex = obj.string("psbt_hex"),
            refundTxHex = obj.string("refund_tx_hex"),
            depositSats = obj.long("deposit_sats"),
            fundingScriptType = obj.string("funding_script_type"),
            sellerRefundAddress = obj.string("seller_refund_address"),
            buyerPeerId = obj.string("buyer_peer_id"),
            sellerPeerId = obj.string("seller_peer_id"),
            buyerBtcAddress = obj.string("buyer_btc_address"),
            buyerPubkeyHex = obj.string("buyer_pubkey_hex"),
            sellerPubkeyHex = obj.string("seller_pubkey_hex"),
            sellerRefundAttestation = obj.string("seller_refund_attestation"),
            buyerAddressAttestation = obj.string("buyer_address_attestation"),
            offerId = obj.string("offer_id"),
            tradeSats = obj.long("trade_sats"),
            fundingTxid = obj.string("funding_txid"),
            fundingVout = obj.long("funding_vout")?.toInt(),
            scriptTemplate = obj.string("script_template"),
            cltvLocktime = obj.long("cltv_locktime"),
        )
    }

    fun parseEvidence(raw: String): InboundEvidence? =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()?.let(::parseEvidence)

    fun parseEvidence(obj: JsonObject): InboundEvidence? {
        val escrowId = runCatching { obj["escrow_id"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return null
        return InboundEvidence(
            escrowId = escrowId,
            submitter = obj.string("submitter") ?: "",
            description = obj.string("description") ?: "",
            mimeType = obj.string("mime_type") ?: "image/jpeg",
            imageBase64 = obj.string("image_base64") ?: "",
        )
    }

    /**
     * Base64 decode with the F4/I5 size gate. Returns null for a blank or
     * oversized payload, or any non-base64 garbage — the host then skips the
     * persist (mirrors the app's `runCatching { Base64.decode(...) }`).
     */
    fun decodeEvidenceImage(base64: String): ByteArray? {
        if (base64.isBlank()) return null
        if (base64.length > MAX_EVIDENCE_BASE64_CHARS) return null
        return runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull()
    }

    // ── decisions ───────────────────────────────────────────────────────

    /** Resolved-feed re-delivery gate (2026-09-02). */
    fun shouldProcess(alreadyResolved: Boolean): Boolean = !alreadyResolved

    /**
     * Guard order mirrors `P2POrchestrator.applyDisputeEvent` exactly:
     * resolved re-delivery → NEW-only opened_by binding → sender-is-party →
     * NEW-only unresolved cap → merge.
     *
     * [unresolvedFromSender] is a lookup because the original only queried the
     * store once the sender was proven to be a party — a stranger must not
     * trigger even a count. Declared `inline` so the host's suspend DAO call is
     * permitted.
     */
    inline fun decideDispute(
        inbound: InboundDispute,
        fromPeerId: String,
        existing: DisputeRecord?,
        unresolvedFromSender: () -> Int,
        nowMs: Long,
    ): DisputeIngestDecision {
        if (!shouldProcess(existing?.resolved == true)) {
            return DisputeIngestDecision.Drop(
                "Dispute ${inbound.escrowId} already resolved — ignoring re-delivery",
                warn = false,
            )
        }
        val isNew = existing == null
        if (!DisputeIngestGate.acceptOpenedBy(isNew = isNew, openedBy = inbound.openedBy, fromPeerId = fromPeerId)) {
            return DisputeIngestDecision.Drop(
                "Dropping NEW dispute ${inbound.escrowId}: openedBy=${inbound.openedBy} != authenticated sender $fromPeerId"
            )
        }
        val senderIsParty = inbound.buyerPeerId == fromPeerId || inbound.sellerPeerId == fromPeerId
        if (!senderIsParty) {
            return DisputeIngestDecision.Drop(
                "Dropping dispute ${inbound.escrowId}: sender $fromPeerId is not a party (openedBy=${inbound.openedBy})"
            )
        }
        if (!DisputeIngestGate.withinCap(isNew = isNew, unresolvedFromSender = unresolvedFromSender())) {
            return DisputeIngestDecision.Drop(
                "Dropping dispute ${inbound.escrowId}: sender ${inbound.openedBy} at unresolved cap"
            )
        }
        return DisputeIngestDecision.Accept(mergeDispute(existing, inbound, nowMs), isNew = isNew)
    }

    /**
     * Merge a wire payload onto the existing row. F2 (2026-09-12): the seven
     * role-key/attestation fields keep a previously persisted value when the
     * re-delivery omits them (a REPLACE upsert would otherwise wipe them);
     * every other field takes the wire value verbatim.
     */
    fun mergeDispute(existing: DisputeRecord?, inbound: InboundDispute, nowMs: Long): DisputeRecord =
        DisputeRecord(
            escrowId = inbound.escrowId,
            openedBy = inbound.openedBy,
            reason = inbound.reason,
            openedAt = inbound.openedAt,
            redeemScriptHex = inbound.redeemScriptHex,
            psbtHex = inbound.psbtHex,
            refundTxHex = inbound.refundTxHex,
            depositSats = inbound.depositSats,
            fundingScriptType = inbound.fundingScriptType,
            sellerRefundAddress = inbound.sellerRefundAddress,
            buyerPeerId = inbound.buyerPeerId,
            sellerPeerId = inbound.sellerPeerId,
            buyerBtcAddress = inbound.buyerBtcAddress ?: existing?.buyerBtcAddress,
            buyerPubkeyHex = inbound.buyerPubkeyHex ?: existing?.buyerPubkeyHex,
            sellerPubkeyHex = inbound.sellerPubkeyHex ?: existing?.sellerPubkeyHex,
            sellerRefundAttestation = inbound.sellerRefundAttestation ?: existing?.sellerRefundAttestation,
            buyerAddressAttestation = inbound.buyerAddressAttestation ?: existing?.buyerAddressAttestation,
            offerId = inbound.offerId ?: existing?.offerId,
            tradeSats = inbound.tradeSats ?: existing?.tradeSats,
            fundingTxid = inbound.fundingTxid ?: existing?.fundingTxid,
            fundingVout = inbound.fundingVout ?: existing?.fundingVout,
            scriptTemplate = inbound.scriptTemplate ?: existing?.scriptTemplate,
            cltvLocktime = inbound.cltvLocktime ?: existing?.cltvLocktime,
            receivedAt = nowMs,
            resolved = false,
        )

    /**
     * Guard order mirrors `P2POrchestrator.applyEvidenceEvent`: the submitter
     * must be the authenticated sender, then the escrow must be known here, then
     * the I5 size cap. The resolved re-delivery gate is the host's (it needs the
     * store lookup) and runs before this.
     *
     * Both escrow lookups are lambdas so the host runs them only AFTER the
     * submitter gate — the original deliberately never touched escrow state for
     * a stranger's evidence, and `getEscrow` has a resume-heal side effect
     * (re-publishes escrow_status). Declared `inline` so the host's suspend DAO
     * calls are permitted.
     */
    inline fun decideEvidence(
        inbound: InboundEvidence,
        fromPeerId: String,
        hasDisputeRow: () -> Boolean,
        hasLocalEscrow: () -> Boolean,
    ): EvidenceIngestDecision {
        if (inbound.submitter != fromPeerId) {
            return EvidenceIngestDecision.Drop(
                "Dropping evidence for ${inbound.escrowId}: submitter ${inbound.submitter} != sender $fromPeerId"
            )
        }
        val disputeRow = hasDisputeRow()
        val localEscrow = hasLocalEscrow()
        if (!EvidenceIngestGate.shouldPersist(disputeRow, localEscrow)) {
            return EvidenceIngestDecision.Drop(
                "Dropping evidence for unknown escrow ${inbound.escrowId} (no dispute row, no local escrow)"
            )
        }
        if (inbound.imageBase64.length > MAX_EVIDENCE_BASE64_CHARS) {
            return EvidenceIngestDecision.Drop(
                "Dropping oversized evidence for ${inbound.escrowId} (${inbound.imageBase64.length} base64 chars)"
            )
        }
        return EvidenceIngestDecision.Accept(
            escrowId = inbound.escrowId,
            submitter = inbound.submitter,
            description = inbound.description,
            mimeType = inbound.mimeType,
            imageData = decodeEvidenceImage(inbound.imageBase64),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun JsonObject.string(key: String): String? =
        runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.long(key: String): Long? =
        runCatching { get(key)?.jsonPrimitive?.longOrNull }.getOrNull()
}
