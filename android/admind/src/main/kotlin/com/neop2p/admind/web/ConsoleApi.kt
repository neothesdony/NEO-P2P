package com.neop2p.admind.web

import com.neop2p.admind.ResolveCommand
import com.neop2p.data.escrow.ArbitrationResolution
import com.neop2p.data.escrow.NetworkParams
import com.neop2p.data.p2p.DisputeRecord
import com.neop2p.data.p2p.DisputeStore
import com.neop2p.data.p2p.EvidenceRecord
import com.neop2p.data.p2p.EvidenceStore
import com.neop2p.data.p2p.ResolutionStore
import com.neop2p.data.p2p.ResolutionSender
import com.neop2p.domain.model.ResolutionDecision
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** A stored dispute, as listed in the console sidebar. */
@Serializable
data class DisputeSummary(
    val escrowId: String,
    val reason: String,
    val openedBy: String,
    val openedAt: Long,
    val receivedAt: Long,
    val resolved: Boolean,
    val evidenceCount: Int,
    val hasPayoutTx: Boolean,
    val hasRefundTx: Boolean,
    val targets: List<String>,
)

/** One evidence attachment, base64-inlined (the app caps these at ~60 KB). */
@Serializable
data class EvidenceView(
    val evidenceId: String,
    val submitter: String,
    val description: String,
    val mimeType: String,
    val imageBase64: String,
    val submittedAt: Long,
)

/** Everything the console needs to review one dispute. No key material. */
@Serializable
data class DisputeDetail(
    val escrowId: String,
    val reason: String,
    val openedBy: String,
    val openedAt: Long,
    val receivedAt: Long,
    val resolved: Boolean,
    val sellerRefundAddress: String?,
    val buyerBtcAddress: String?,
    val hasPayoutTx: Boolean,
    val hasRefundTx: Boolean,
    val targets: List<String>,
    val evidence: List<EvidenceView>,
)

/**
 * A deliberate refusal with the HTTP status it maps to. Thrown (as a failed
 * [Result]) by every [ConsoleApi] operation so the server is a pure status
 * mapper and the rules stay unit-testable.
 */
class Refusal(val status: Int, override val message: String) : Exception(message)

/** `POST /api/disputes/{id}/plan` body. */
@Serializable data class PlanRequest(val decision: String)

/**
 * The verified destination preview: exactly what [ResolveCommand.plan] parsed,
 * so the operator can eyeball where the sats will go before authorizing.
 */
@Serializable data class PlanView(
    val decision: String,
    val outputs: List<String>,
    val targets: List<String>,
)

/** `POST /api/disputes/{id}/resolve` body. `confirm` must be `true`. */
@Serializable data class ResolveRequest(
    val decision: String,
    val notes: String? = null,
    val confirm: Boolean = false,
)

/**
 * The result of a broadcast. `signedTxHex` and `arbitratorSigHex` are public
 * artifacts (they are what the parties receive); the private key is never
 * returned by any endpoint.
 */
@Serializable data class ResolveView(
    val decision: String,
    val delivered: Boolean,
    val signedTxHex: String?,
    val arbitratorSigHex: String?,
    val message: String,
)

/**
 * The console's testable core: it reads/writes the daemon's SQLite stores and
 * calls `ResolveCommand` for planning and signing. It holds no Ktor types, so
 * every rule (status mapping, per-escrow serialization) is unit-testable
 * without an HTTP round trip. [ConsoleServer] is a thin adapter over this.
 */
class ConsoleApi(
    private val disputes: DisputeStore,
    private val evidence: EvidenceStore,
    private val resolutions: ResolutionStore,
    private val arbitratorPrivKeyHex: () -> String,
    private val senderProvider: suspend () -> ResolutionSender,
    private val sign: (DisputeRecord, String, String) -> Result<String> =
        { record, txHex, keyHex -> ArbitrationResolution.sign(record, txHex, keyHex) },
) {

    fun list(): List<DisputeSummary> = disputes.all().map(::summary)

    fun detail(escrowId: String): Result<DisputeDetail> {
        val record = disputes.getById(escrowId) ?: return Result.failure(notFound(escrowId))
        return Result.success(
            DisputeDetail(
                escrowId = record.escrowId,
                reason = record.reason,
                openedBy = record.openedBy,
                openedAt = record.openedAt,
                receivedAt = record.receivedAt,
                resolved = record.resolved,
                sellerRefundAddress = record.sellerRefundAddress,
                buyerBtcAddress = record.buyerBtcAddress,
                hasPayoutTx = record.psbtHex != null,
                hasRefundTx = record.refundTxHex != null,
                targets = ArbitrationResolution.targets(record),
                evidence = evidence.forEscrow(escrowId).map(::evidenceView),
            )
        )
    }

    fun plan(escrowId: String, decisionRaw: String): Result<PlanView> {
        val decision = parseDecision(decisionRaw)
            ?: return Result.failure(Refusal(400, "Unknown decision '$decisionRaw'"))
        val record = disputes.getById(escrowId) ?: return Result.failure(notFound(escrowId))
        val plan = ResolveCommand.plan(record, decision, NetworkParams.current()).getOrElse {
            return Result.failure(Refusal(422, it.message ?: "Refused"))
        }
        return Result.success(PlanView(decision.name, plan.outputs, plan.targets))
    }

    private fun parseDecision(raw: String): ResolutionDecision? =
        runCatching { ResolutionDecision.valueOf(raw.trim().uppercase()) }.getOrNull()

    /**
     * Sign and deliver a ruling. The dispute is re-read under a per-escrow lock
     * so two concurrent requests can never both sign and broadcast; the key is
     * only materialized inside [ResolveCommand.run] after every gate passes.
     */
    suspend fun resolve(
        escrowId: String,
        decisionRaw: String,
        notes: String?,
        confirm: Boolean,
    ): Result<ResolveView> {
        val record = disputes.getById(escrowId) ?: return Result.failure(notFound(escrowId))
        if (record.resolved) return Result.failure(alreadyResolved(escrowId))
        if (!confirm) return Result.failure(Refusal(400, "confirm must be true"))
        val decision = parseDecision(decisionRaw)
            ?: return Result.failure(Refusal(400, "Unknown decision '$decisionRaw'"))

        return resolveLock(escrowId).withLock {
            // Re-read under the lock: a parallel request may have resolved it.
            val current = disputes.getById(escrowId) ?: return@withLock Result.failure(notFound(escrowId))
            if (current.resolved) return@withLock Result.failure(alreadyResolved(escrowId))

            val result = ResolveCommand.run(
                disputes = disputes,
                resolutions = resolutions,
                escrowId = escrowId,
                decision = decision,
                notes = notes,
                confirm = true,
                arbitratorPrivKeyHex = arbitratorPrivKeyHex,
                senderProvider = senderProvider,
                sign = sign,
                out = {},
            )
            if (result.delivered) {
                Result.success(
                    ResolveView(
                        decision = decision.name,
                        delivered = true,
                        signedTxHex = result.signedTxHex,
                        arbitratorSigHex = result.arbitratorSigHex,
                        message = result.message,
                    )
                )
            } else {
                Result.failure(Refusal(502, result.message))
            }
        }
    }

    private fun summary(record: DisputeRecord) = DisputeSummary(
        escrowId = record.escrowId,
        reason = record.reason,
        openedBy = record.openedBy,
        openedAt = record.openedAt,
        receivedAt = record.receivedAt,
        resolved = record.resolved,
        evidenceCount = evidence.forEscrow(record.escrowId).size,
        hasPayoutTx = record.psbtHex != null,
        hasRefundTx = record.refundTxHex != null,
        targets = ArbitrationResolution.targets(record),
    )

    private fun evidenceView(record: EvidenceRecord) = EvidenceView(
        evidenceId = record.evidenceId,
        submitter = record.submitterPeerId,
        description = record.description,
        mimeType = record.mimeType,
        imageBase64 = Base64.getEncoder().encodeToString(record.imageData),
        submittedAt = record.submittedAt,
    )

    private val resolveLocks = ConcurrentHashMap<String, Mutex>()

    private fun resolveLock(escrowId: String): Mutex = resolveLocks.computeIfAbsent(escrowId) { Mutex() }

    private fun alreadyResolved(escrowId: String) = Refusal(409, "Dispute $escrowId is already resolved")

    private fun notFound(escrowId: String) = Refusal(404, "No dispute stored for escrow $escrowId")
}
