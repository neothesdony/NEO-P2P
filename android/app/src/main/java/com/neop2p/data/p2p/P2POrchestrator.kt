package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.local.DeletedOfferStore
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.p2p.protocol.AppMessage
import com.neop2p.data.p2p.protocol.EnvelopeCodec
import com.neop2p.data.p2p.queue.OfflineQueue
import com.neop2p.data.p2p.routing.ChatRouter
import com.neop2p.data.p2p.routing.OfferRouter
import com.neop2p.data.p2p.store.PeerRegistry
import com.neop2p.data.reputation.ReputationSystem
import com.neop2p.data.reputation.ReputationSystem.Attestation
import com.neop2p.data.reputation.ReputationSystem.AttestationOutcome
import com.neop2p.service.AppForegroundTracker
import com.neop2p.service.NotificationDispatcher
import com.neop2p.service.WalletWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level coordinator for the P2P message-routing pipeline.
 *
 * Owns the lifecycle of the transport, identity, Nostr, reputation, and router
 * components, and dispatches inbound [AppMessage]s to the correct handler.
 * [start] is idempotent; [stop] tears down the network-facing components.
 */
@Singleton
class P2POrchestrator @Inject constructor(
    private val identityManager: IdentityManager,
    private val p2pTransport: HybridP2PTransport,
    private val signal: SignalProtocol,
    private val nostrClient: NostrClient,
    private val reputation: ReputationSystem,
    private val peerRegistry: PeerRegistry,
    private val queue: OfflineQueue,
    private val chatRouter: ChatRouter,
    private val offerRouter: OfferRouter,
    private val escrowService: EscrowService,
    private val offerDao: OfferDao,
    private val deletedOfferStore: DeletedOfferStore,
    private val webRTCManager: WebRTCManager,
    private val notificationDispatcher: NotificationDispatcher,
    private val appForegroundTracker: AppForegroundTracker,
    private val walletWatcher: WalletWatcher,
    private val scope: CoroutineScope
) {
    @Volatile private var running = false
    @Volatile private var inboundJob: Job? = null
    @Volatile private var notifyInboundJob: Job? = null
    @Volatile private var offerStatusJob: Job? = null
    @Volatile private var offerDeletedJob: Job? = null
    @Volatile private var escrowTransitionJob: Job? = null
    @Volatile private var escrowSweepJob: Job? = null

    /** Offer ids already notified as matched, to dedupe re-announcements. */
    private val notifiedOfferMatches = mutableSetOf<String>()

    suspend fun start(): Result<Unit> {
        if (running) return Result.success(Unit)
        running = true
        return try {
            // Non-fatal: log and continue if Signal init fails so the rest of the
            // pipeline (transport, identity, Nostr) can still come up.
            signal.initialize().onFailure {
                Log.w(TAG, "Signal init failed (continuing): ${it.message}")
            }
            // WebRTC needs the factory up before any peer connection is created.
            webRTCManager.initialize().onFailure {
                Log.w(TAG, "WebRTC init failed (continuing): ${it.message}")
            }
            p2pTransport.start()
            val identity = identityManager.getOrCreateIdentity()
            nostrClient.connect(identity.nostrPubkeyHex)
            reputation.initialize()
            // Fix 2: scan for stale escrows on startup so a FUNDED-but-stalled
            // escrow auto-refunds (and an unfunded one auto-cancels). Idempotent.
            escrowService.initialize()
            offerRouter.startListening(scope)
            listenInbound()
            launchPeerDrain()
            consumeAttestations()
            notifyInboundChat()
            collectOfferStatuses()
            collectOfferDeletions()
            collectOwnDeletions()
            collectEscrowTransitions()
            sweepStaleEscrows()
            walletWatcher.start(scope)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Orchestrator start failed", e)
            running = false
            Result.failure(e)
        }
    }

    private fun listenInbound() {
        inboundJob?.cancel()
        inboundJob = scope.launch {
            p2pTransport.incomingMessages.collect { env ->
                // WebRTC signaling (SDP/ICE) is NOT an AppMessage — route it
                // straight to WebRTCManager before the codec rejects it.
                if (env.type == WebRTCManager.SIGNAL_TOPIC) {
                    if (env.fromPeerId.isNotBlank()) {
                        webRTCManager.handleInboundSignal(env.fromPeerId, env.data)
                    }
                    return@collect
                }

                val msg = EnvelopeCodec.decode(env) ?: return@collect
                when (msg) {
                    // msg.from is the peer requesting our bundle; reply to them.
                    is AppMessage.PreKeyRequest -> {
                        signal.sendPreKeyBundle(msg.from)
                            .onSuccess { bundle -> queue.send(msg.from, bundle) }
                    }
                    is AppMessage.PreKeyBundle -> {
                        // Peer replied with their bundle: establish the session,
                        // then reply with OUR bundle ONLY if we had no session with
                        // them before this bundle arrived. This completes the
                        // handshake (both sides end up with a key) while staying
                        // terminating — replying unconditionally would make every
                        // bundle trigger another bundle forever (handshake loop).
                        val hadSession = signal.hasStoredSession(msg.from)
                        val bundle = signal.deserializeBundle(msg.bundle)
                        signal.createSession(msg.from, bundle, authenticated = env.authenticated)
                            .onSuccess {
                                if (!hadSession) {
                                    signal.sendPreKeyBundle(msg.from)
                                        .onSuccess { reply -> queue.send(msg.from, reply) }
                                }
                            }
                    }
                    is AppMessage.Chat -> chatRouter.receiveChat(msg)
                    is AppMessage.Offer -> offerRouter.receiveOffer(msg)
                }
            }
        }
    }

    /**
     * Drains the offline queue for a peer once it reports online. Returns whether
     * every pending message was successfully sent over the transport.
     */
    private suspend fun drainPending(peerId: String) {
        if (!running) return
        queue.drainFor(peerId) { msg ->
            if (!running) return@drainFor false
            val env = EnvelopeCodec.encode(msg)
            p2pTransport.send(peerId, env.data, env.type).isSuccess
        }
    }

    private fun launchPeerDrain() {
        scope.launch {
            // Drain queued messages for any peer that comes online. `authenticated`
            // is advisory only (sessions are bound by identity checks), so drain
            // for every online peer — otherwise relay-only peers never receive
            // their queued pre-key bundles and chat.
            //
            // NOTE: `collect` (not collectLatest) — a new peers emission must NOT
            // cancel an in-flight drain, or queued chat starves behind bursts of
            // handshake messages that keep restarting it.
            peerRegistry.peers
                .collect { peers ->
                    for ((peerId, info) in peers) {
                        if (info.isOnline) drainPending(peerId)
                    }
                }
        }
    }

    /**
     * Verify and apply attestations received via Nostr (kind:33335).
     *
     * NostrClient persists the raw event; this is the trust boundary — the
     * signature is verified against the signer's stored pubkey BEFORE the
     * reputation update applies. Invalid or unverifiable attestations are
     * dropped (the persisted row stays as evidence but never affects scores).
     */
    private fun consumeAttestations() {
        scope.launch {
            nostrClient.attestations.collect { entity ->
                try {
                    val outcome = if (entity.outcome == "POSITIVE") {
                        AttestationOutcome.POSITIVE
                    } else {
                        AttestationOutcome.NEGATIVE
                    }
                    val attestation = Attestation(
                        fromPeer = entity.from_peer_id,
                        targetPeer = entity.target_peer_id,
                        outcome = outcome,
                        volumeSats = entity.volume_sats,
                        timestamp = entity.timestamp,
                        signature = hexToBytes(entity.signature_hex)
                    )
                    reputation.processAttestation(attestation)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to consume attestation: ${e.message}")
                }
            }
        }
    }

    /**
     * Notify the user of inbound chat messages received via the E2EE pipeline.
     *
     * Consumes [ChatRouter.incomingChats] — which carries the REAL offer id
     * (the legacy signal.incomingMessages collector had no offer context and
     * collapsed every conversation into a single notification slot). Only
     * posts while the app is NOT in the foreground; the chat screen cancels
     * its own per-conversation notifications on entry.
     */
    private fun notifyInboundChat() {
        notifyInboundJob?.cancel()
        notifyInboundJob = scope.launch {
            chatRouter.incomingChats.collect { incoming ->
                // Suppress only when the user is actively looking at THIS
                // conversation (the chat screen cancels its own per-conversation
                // notifications on entry). If the app is foregrounded but the
                // user is on another screen (home, escrow, wallet), still ping
                // them — otherwise a buyer sitting on the home screen would
                // never learn the seller shared their bank details.
                if (appForegroundTracker.openConversationKey.value == incoming.offerId) {
                    return@collect
                }
                val text = runCatching { incoming.plaintext.toString(Charsets.UTF_8) }
                    .getOrDefault("")
                notificationDispatcher.notifyChat(
                    offerId = incoming.offerId,
                    peerId = incoming.fromPeerId,
                    senderLabel = "",
                    message = text
                )
            }
        }
    }

    /** Notify when one of the user's offers is matched by a foreign peer. */
    private fun collectOfferStatuses() {
        offerStatusJob?.cancel()
        offerStatusJob = scope.launch {
            nostrClient.offerStatusUpdates.collect { (offerId, _, matchedPeerId) ->
                if (matchedPeerId.isNullOrBlank()) return@collect
                val myPeerId = runCatching { identityManager.getOrCreateIdentity().peerId }
                    .getOrNull() ?: return@collect
                if (matchedPeerId.equals(myPeerId, ignoreCase = true)) return@collect
                // Guard against duplicate re-announcements: only notify once per
                // offer id for this process run.
                if (!notifiedOfferMatches.add(offerId)) return@collect
                notificationDispatcher.notifyOfferMatched(offerId, matchedPeerId)
            }
        }
    }

    /**
     * Backfill tombstones from OUR OWN NIP-09 deletions replayed by the relay.
     * Offers deleted before the tombstone fix have no local record — without
     * this, their replayed offer events would resurrect them once more.
     */
    private fun collectOwnDeletions() {
        scope.launch {
            nostrClient.ownDeletions.collect { deletedEventId ->
                val entity = offerDao.getOfferByEventId(deletedEventId)
                if (entity != null) {
                    offerDao.delete(entity)
                    deletedOfferStore.markDeleted(entity.offer_id, deletedEventId)
                } else {
                    deletedOfferStore.markDeleted(deletedEventId)
                }
            }
        }
    }

    /**
     * Apply a peer's NIP-09 deletion: remove the offer locally (by event id),
     * tombstone it so a relay replay can't resurrect it, and notify.
     */
    private fun collectOfferDeletions() {
        offerDeletedJob?.cancel()
        offerDeletedJob = scope.launch {
            nostrClient.deletions.collect { deletedEventId ->
                // The deletion references the ORIGINAL event id; resolve it to
                // the local offer row so both the row and the tombstone drop.
                val entity = offerDao.getOfferByEventId(deletedEventId)
                if (entity != null) {
                    offerDao.delete(entity)
                    deletedOfferStore.markDeleted(entity.offer_id, deletedEventId)
                } else {
                    // Not stored locally (or id mismatch) — still tombstone the
                    // event id so a later replay can't insert it.
                    deletedOfferStore.markDeleted(deletedEventId)
                }
                notificationDispatcher.notifyOfferDeleted(deletedEventId)
            }
        }
    }

    /** Map escrow transitions to user-facing notifications. */
    private fun collectEscrowTransitions() {
        escrowTransitionJob?.cancel()
        escrowTransitionJob = scope.launch {
            escrowService.transitions.collect { t ->
                val mapped = when (t.status) {
                    "created", "funding" -> "Escrow created" to "Escrow opened — awaiting seller funding"
                    "funded" -> "Escrow funded" to "Seller deposited funds — on-chain verified"
                    "signed" -> "Escrow signed" to "Transaction signed by both parties"
                    "paid" -> "Payment marked as sent" to "Buyer says the fiat payment was sent — release or dispute within the payment window"
                    "released" -> "Escrow released" to "Funds released to the buyer"
                    "disputed" -> "Escrow disputed" to "A dispute was opened"
                    "resolving" -> "Dispute resolving" to "Arbitration in progress"
                    "refunded" -> "Escrow refunded" to "Funds returned to the seller"
                    "cancelled" -> "Escrow cancelled" to "The escrow was cancelled"
                    else -> null
                }
                mapped?.let { (title, message) ->
                    notificationDispatcher.notifyEscrow(t.escrowId, t.status, title, message)
                }
            }
        }
    }

    /**
     * Periodically re-run the stale-escrow sweep. The funding window (30 min)
     * and funded-refund window (6 h) are enforced from a single scan at
     * startup otherwise, so a long-lived process would never auto-cancel or
     * auto-refund a stalled escrow. Sweeping every 60s keeps the deadlines
     * honest while the foreground service is up (idempotent: terminal
     * statuses are skipped, so re-scans are cheap no-ops).
     */
    private fun sweepStaleEscrows() {
        escrowSweepJob?.cancel()
        escrowSweepJob = scope.launch {
            while (isActive) {
                escrowService.expireStaleEscrows()
                delay(ESCROW_SWEEP_INTERVAL_MS)
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    suspend fun stop() {
        if (!running) return
        running = false
        inboundJob?.cancel()
        inboundJob = null
        notifyInboundJob?.cancel()
        notifyInboundJob = null
        offerStatusJob?.cancel()
        offerStatusJob = null
        offerDeletedJob?.cancel()
        offerDeletedJob = null
        escrowTransitionJob?.cancel()
        escrowTransitionJob = null
        escrowSweepJob?.cancel()
        escrowSweepJob = null
        notifiedOfferMatches.clear()
        nostrClient.disconnect()
        p2pTransport.stop()
    }

    companion object {
        private const val TAG = "P2POrchestrator"
        private const val ESCROW_SWEEP_INTERVAL_MS = 60_000L
    }
}
