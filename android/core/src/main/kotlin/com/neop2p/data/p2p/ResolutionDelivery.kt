package com.neop2p.data.p2p

/**
 * Canonical, storage-agnostic shape of an outbound arbitrator resolution
 * awaiting LXMF delivery (Phase 1c). Mirrors `PendingArbitrationStore`'s
 * `pending_resolution_*` rows so the Android host and the headless daemon
 * persist identical data.
 *
 * `targets` is ALWAYS the set of peers still needing the message — a
 * successfully-delivered peer is removed. A row exists only while at least one
 * target has not acked; the sweep removes it once every target acks.
 */
data class PendingResolution(
    val escrowId: String,
    val decision: String,
    val arbitratorSigHex: String,
    val notes: String?,
    val sellerRefundAddress: String?,
    val signedTxHex: String?,
    val targets: List<String>,
)

/** Host-provided durability for outbound resolutions awaiting delivery. */
interface ResolutionStore {
    fun save(resolution: PendingResolution)
    fun load(escrowId: String): PendingResolution?
    fun all(): List<PendingResolution>
    fun remove(escrowId: String)
    fun clear()
}

/** Sends one resolution to one peer; `true` = delivered (acked) to that peer. */
interface ResolutionSender {
    suspend fun sendResolution(
        toPeerId: String,
        escrowId: String,
        decision: String,
        arbitratorSigHex: String,
        notes: String?,
        sellerRefundAddress: String?,
        signedTxHex: String?,
    ): Boolean
}

/**
 * Delivers an arbitrator resolution to the dispute parties with durable retry
 * (Phase 1c), shared by `:app` and `:admind`.
 *
 * Semantics (the INTENDED ones — the pre-1c app loop was inverted):
 *  - a target is retained iff its send FAILED, so the next sweep retries only
 *    undelivered peers;
 *  - the row is removed only when every target has acked;
 *  - a resolution for an already-resolved dispute is dropped without sending.
 *
 * Callers must supply a non-empty target list; [broadcast] with no targets
 * trivially "succeeds" and is not a supported way to resolve a dispute.
 */
class ResolutionBroadcaster(
    private val sender: ResolutionSender,
    private val store: ResolutionStore,
    private val isDisputeResolved: suspend (escrowId: String) -> Boolean = { false },
) {

    /**
     * First delivery attempt. Persists ONLY the failed targets (removing any
     * prior row) and returns true when every target acked.
     */
    suspend fun broadcast(resolution: PendingResolution): Boolean {
        val failed = deliver(resolution, resolution.targets)
        if (failed.isEmpty()) store.remove(resolution.escrowId)
        else store.save(resolution.copy(targets = failed))
        return failed.isEmpty()
    }

    /**
     * Sweep every stored row once. Returns the number of rows completed
     * (delivered in full) or dropped (already-resolved).
     */
    suspend fun retryAll(): Int {
        var completed = 0
        for (pending in store.all()) {
            if (isDisputeResolved(pending.escrowId)) {
                store.remove(pending.escrowId)
                completed++
                continue
            }
            val failed = deliver(pending, pending.targets)
            if (failed.isEmpty()) {
                store.remove(pending.escrowId)
                completed++
            } else {
                store.save(pending.copy(targets = failed))
            }
        }
        return completed
    }

    private suspend fun deliver(pending: PendingResolution, targets: List<String>): List<String> =
        targets.filterNot { target ->
            sender.sendResolution(
                toPeerId = target,
                escrowId = pending.escrowId,
                decision = pending.decision,
                arbitratorSigHex = pending.arbitratorSigHex,
                notes = pending.notes,
                sellerRefundAddress = pending.sellerRefundAddress,
                signedTxHex = pending.signedTxHex,
            )
        }
}
