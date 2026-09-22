package com.neop2p.data.p2p

/**
 * Canonical, storage-agnostic shape of an arbitrated dispute. Mirrors the
 * Room `arbitrator_disputes` table (entity `ArbitratorDisputeEntity`) so the
 * Android host and the headless arbitrator daemon persist identical rows.
 */
data class DisputeRecord(
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
    val receivedAt: Long,
    val resolved: Boolean = false,
)

/**
 * Canonical shape of a dispute-evidence row. Mirrors the Room
 * `dispute_evidence` table (entity `DisputeEvidenceEntity`).
 */
data class EvidenceRecord(
    val evidenceId: String,
    val escrowId: String,
    val submitterPeerId: String,
    val description: String,
    val mimeType: String,
    val imageData: ByteArray,
    val submittedAt: Long,
)

/**
 * Host-provided durability for disputes. `:app` backs it with Room; the admin
 * daemon with SQLite-JDBC. The arbitrator has NO local escrow row, so this is
 * the sole durable record of a received dispute.
 */
interface DisputeStore {
    fun getById(escrowId: String): DisputeRecord?
    fun countUnresolvedBySender(openedBy: String): Int
    fun upsert(record: DisputeRecord)
    fun markResolved(escrowId: String)
    fun all(): List<DisputeRecord>
    fun clear()
}

/** Host-provided durability for dispute evidence. */
interface EvidenceStore {
    fun forEscrow(escrowId: String): List<EvidenceRecord>
    fun insert(record: EvidenceRecord)
    fun all(): List<EvidenceRecord>
    fun clear()
}
