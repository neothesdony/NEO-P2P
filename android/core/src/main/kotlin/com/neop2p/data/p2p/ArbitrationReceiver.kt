package com.neop2p.data.p2p

import com.neop2p.NeoLog
import kotlinx.coroutines.flow.collect

/**
 * Host-agnostic arbitration receiver (Phase 1b). Applies the SAME inbound
 * dispatch the app's `P2POrchestrator` signaling collector does, but only for
 * the two messages the arbitrator ingests:
 *
 *   rate limit (keyed by the unclaimable sender destination) ->
 *   F1 verified-sender binding -> parse -> [ArbitrationIngest] decision -> persist.
 *
 * `resolution` is a SEND-side concern (Phase 1c) and is ignored here. Because
 * the daemon has no local escrow, every escrow lookup is supplied by the host.
 */
class ArbitrationReceiver(
    private val transport: TransportGateway,
    private val disputes: DisputeStore,
    private val evidence: EvidenceStore,
    /**
     * Whether the host has a local escrow for this id (party side). The
     * arbitrator has none; kept injectable so the class stays reusable.
     */
    private val localEscrowExists: (escrowId: String) -> Boolean = { false },
    private val evidenceIdFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val rateLimiter: PerPeerRateLimiter = PerPeerRateLimiter(),
) {
    /** Collects forever; returns only when the transport flow completes. */
    suspend fun run() {
        transport.incomingMessages.collect { handle(it) }
    }

    /** Evicts idle rate-limiter buckets; the host calls this from a sweep. */
    fun evictIdleLimiterBuckets(nowMs: Long = this.nowMs()) {
        rateLimiter.evictIdle(nowMs)
    }

    /** One message. Exposed for tests and a future web-console feed. */
    suspend fun handle(message: P2PTransport.TransportMessage) {
        // F4: key the limiter by the unclaimable sender destination, not the
        // self-asserted peerId.
        val limiterKey = message.senderDestHash.ifBlank { message.fromPeerId }
        if (!rateLimiter.tryAcquire(limiterKey, nowMs())) {
            NeoLog.w(TAG, "Dropping inbound ${message.type} from $limiterKey: rate limit exceeded")
            return
        }
        when (message.type) {
            ArbitrationIngest.TYPE_DISPUTE -> {
                if (!transport.isVerifiedSender(message.fromPeerId, message.senderDestHash)) {
                    NeoLog.w(
                        TAG,
                        "Dropping dispute: sender ${message.fromPeerId} has no verified identity binding (peer must upgrade)"
                    )
                    return
                }
                val inbound = ArbitrationIngest.parseDispute(message.data.toString(Charsets.UTF_8)) ?: return
                ingestDispute(inbound, message.fromPeerId)
            }
            ArbitrationIngest.TYPE_EVIDENCE -> {
                if (!transport.isVerifiedSender(message.fromPeerId, message.senderDestHash)) {
                    NeoLog.w(
                        TAG,
                        "Dropping evidence: sender ${message.fromPeerId} has no verified identity binding (peer must upgrade)"
                    )
                    return
                }
                val inbound = ArbitrationIngest.parseEvidence(message.data.toString(Charsets.UTF_8)) ?: return
                ingestEvidence(inbound, message.fromPeerId)
            }
            else -> Unit
        }
    }

    private fun ingestDispute(inbound: InboundDispute, fromPeerId: String) {
        val existing = disputes.getById(inbound.escrowId)
        val decision = ArbitrationIngest.decideDispute(
            inbound = inbound,
            fromPeerId = fromPeerId,
            existing = existing,
            unresolvedFromSender = { disputes.countUnresolvedBySender(inbound.openedBy) },
            nowMs = nowMs(),
        )
        when (decision) {
            is DisputeIngestDecision.Drop -> {
                if (decision.warn) NeoLog.w(TAG, decision.reason) else NeoLog.i(TAG, decision.reason)
            }
            is DisputeIngestDecision.Accept -> {
                disputes.upsert(decision.record)
                NeoLog.i(
                    TAG,
                    "Persisted arbitrator dispute ${inbound.escrowId} (new=${decision.isNew})"
                )
            }
        }
    }

    private fun ingestEvidence(inbound: InboundEvidence, fromPeerId: String) {
        // Resolved re-delivery guard runs before any escrow/store touch.
        val existing = disputes.getById(inbound.escrowId)
        if (!ArbitrationIngest.shouldProcess(existing?.resolved == true)) {
            NeoLog.i(TAG, "Evidence for ${inbound.escrowId} after resolution — ignoring re-delivery")
            return
        }
        val decision = ArbitrationIngest.decideEvidence(
            inbound = inbound,
            fromPeerId = fromPeerId,
            hasDisputeRow = { disputes.getById(inbound.escrowId) != null },
            hasLocalEscrow = { localEscrowExists(inbound.escrowId) },
        )
        when (decision) {
            is EvidenceIngestDecision.Drop -> NeoLog.w(TAG, decision.reason)
            is EvidenceIngestDecision.Accept -> {
                val bytes = decision.imageData
                if (bytes == null || bytes.isEmpty()) return
                val duplicate = evidence.forEscrow(inbound.escrowId).any {
                    it.submitterPeerId == decision.submitter &&
                        it.description == decision.description &&
                        it.imageData.size == bytes.size &&
                        it.imageData.contentEquals(bytes)
                }
                if (duplicate) return
                evidence.insert(
                    EvidenceRecord(
                        evidenceId = evidenceIdFactory(),
                        escrowId = inbound.escrowId,
                        submitterPeerId = decision.submitter,
                        description = decision.description,
                        mimeType = decision.mimeType,
                        imageData = bytes,
                        submittedAt = nowMs(),
                    )
                )
                NeoLog.i(TAG, "Persisted evidence for ${inbound.escrowId}")
            }
        }
    }

    private companion object {
        const val TAG = "ArbitrationReceiver"
    }
}
