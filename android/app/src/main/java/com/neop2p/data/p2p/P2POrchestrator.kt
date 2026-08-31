package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.local.DeletedOfferStore
import com.neop2p.data.local.dao.ArbitratorDisputeDao
import com.neop2p.data.local.dao.DisputeEvidenceDao
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.entity.ArbitratorDisputeEntity
import com.neop2p.data.local.entity.DisputeEvidenceEntity
import com.neop2p.data.local.toDomain
import com.neop2p.data.p2p.protocol.AppMessage
import com.neop2p.data.p2p.protocol.EnvelopeCodec
import com.neop2p.data.p2p.queue.OfflineQueue
import com.neop2p.data.p2p.routing.ChatRouter
import com.neop2p.data.p2p.routing.OfferRouter
import com.neop2p.data.p2p.routing.EscrowRouter
import com.neop2p.data.p2p.store.PeerRegistry
import com.neop2p.data.reputation.ReputationSystem
import com.neop2p.data.reputation.ReputationSystem.Attestation
import com.neop2p.data.reputation.ReputationSystem.AttestationOutcome
import com.neop2p.domain.model.EscrowStatus
import com.neop2p.domain.model.ResolutionDecision
import com.neop2p.service.AppForegroundTracker
import com.neop2p.service.NotificationDispatcher
import com.neop2p.service.WalletWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val identityManager: IdentityManager,
    private val p2pTransport: HybridP2PTransport,
    private val rnsTransport: RnsTransport,
    private val signal: SignalProtocol,
    private val nostrClient: NostrClient,
    private val reputation: ReputationSystem,
    private val peerRegistry: PeerRegistry,
    private val queue: OfflineQueue,
    private val chatRouter: ChatRouter,
    private val offerRouter: OfferRouter,
    private val escrowRouter: EscrowRouter,
    private val escrowService: EscrowService,
    private val offerDao: OfferDao,
    private val deletedOfferStore: DeletedOfferStore,
    private val webRTCManager: WebRTCManager,
    private val notificationDispatcher: NotificationDispatcher,
    private val appForegroundTracker: AppForegroundTracker,
    private val walletWatcher: WalletWatcher,
    private val disputeEvidenceDao: DisputeEvidenceDao,
    private val arbitratorDisputeDao: ArbitratorDisputeDao,
    private val pendingDisputeStore: com.neop2p.data.local.PendingDisputeStore,
    private val scope: CoroutineScope
) {
    @Volatile private var running = false
    @Volatile private var inboundJob: Job? = null
    @Volatile private var notifyInboundJob: Job? = null
    @Volatile private var offerStatusJob: Job? = null
    @Volatile private var offerDeletedJob: Job? = null
    @Volatile private var escrowTransitionJob: Job? = null
    @Volatile private var escrowSweepJob: Job? = null
    @Volatile private var disputeJob: Job? = null
    @Volatile private var evidenceJob: Job? = null
    @Volatile private var resolutionJob: Job? = null

    /**
     * Persistent dedup for match/deletion notifications. The relay replays
     * history on every subscription, so in-memory sets alone re-fire old
     * notifications after each app restart. Keyed by offer/event id.
     */
    private val notifiedPrefs by lazy {
        context.getSharedPreferences("neop2p_notified_events", android.content.Context.MODE_PRIVATE)
    }

    private fun alreadyNotified(key: String): Boolean = notifiedPrefs.contains(key)

    private fun markNotified(key: String) {
        notifiedPrefs.edit().putBoolean(key, true).apply()
    }

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
            // Dual-run: RNS starts alongside the legacy stack. Never fatal —
            // if RNS fails the app keeps working on libp2p/Nostr until Phase 2.
            rnsTransport.start().onFailure {
                Log.w(TAG, "RNS start failed (continuing on legacy stack): ${it.message}")
            }
            val identity = identityManager.getOrCreateIdentity()
            nostrClient.connect(identity.nostrPubkeyHex)
            reputation.initialize()
            // Fix 2: scan for stale escrows on startup so a FUNDED-but-stalled
            // escrow auto-refunds (and an unfunded one auto-cancels). Idempotent.
            escrowService.initialize()
            offerRouter.startListening(scope)
            escrowRouter.startListening(scope)
            listenInbound()
            launchPeerDrain()
            consumeAttestations()
            notifyInboundChat()
            collectOfferStatuses()
            collectOfferDeletions()
            collectOwnDeletions()
            collectEscrowTransitions()
            sweepStaleEscrows()
            consumeDisputes()
            consumeEvidence()
            consumeResolutions()
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
            // Dual-run: collect from BOTH the legacy hybrid transport and RNS.
            // RNS messages carry the same EnvelopeCodec bytes (type = LXMF
            // title, data = envelope), so the AppMessage dispatch below is
            // transport-agnostic.
            merge(
                p2pTransport.incomingMessages,
                rnsTransport.incomingMessages
            ).collect { env ->
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
        // Phase 3: LXMF signaling (offer_status / escrow_status / dispute /
        // evidence / resolution / offer_request / offer) arrives as raw JSON
        // in the LXMF title + FIELD_CUSTOM_DATA — NOT as EnvelopeCodec
        // AppMessages. Route them to the same handlers the Nostr collectors
        // use so both transports converge.
        scope.launch {
            rnsTransport.incomingMessages.collect { env ->
                when (env.type) {
                    "offer_status" -> {
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                env.data.toString(Charsets.UTF_8)
                            ).jsonObject
                        }.getOrNull() ?: return@collect
                        offerRouter.applyOfferStatus(
                            offerId = obj["offer_id"]?.jsonPrimitive?.content ?: return@collect,
                            status = obj["status"]?.jsonPrimitive?.content ?: return@collect,
                            matchedPeerId = obj["matched_peer_id"]?.jsonPrimitive?.content,
                            buyerBtcAddress = obj["buyer_btc_address"]?.jsonPrimitive?.content,
                            authorPeerId = obj["author_peer_id"]?.jsonPrimitive?.content
                        )
                    }
                    "escrow_status" -> {
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                env.data.toString(Charsets.UTF_8)
                            ).jsonObject
                        }.getOrNull() ?: return@collect
                        escrowRouter.ingestEscrowStatus(obj)
                    }
                    "dispute" -> {
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                env.data.toString(Charsets.UTF_8)
                            ).jsonObject
                        }.getOrNull() ?: return@collect
                        applyDisputeEvent(obj)
                    }
                    "evidence" -> {
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                env.data.toString(Charsets.UTF_8)
                            ).jsonObject
                        }.getOrNull() ?: return@collect
                        applyEvidenceEvent(obj)
                    }
                    "resolution" -> {
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                env.data.toString(Charsets.UTF_8)
                            ).jsonObject
                        }.getOrNull() ?: return@collect
                        applyResolutionEvent(obj)
                    }
                    "offer_request" -> {
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                env.data.toString(Charsets.UTF_8)
                            ).jsonObject
                        }.getOrNull() ?: return@collect
                        val offerId = obj["offer_id"]?.jsonPrimitive?.content ?: return@collect
                        val offer = offerDao.getOfferSync(offerId)?.toDomain()
                        if (offer != null) {
                            // Rebuild the offer JSON the same way the Nostr
                            // path publishes it (minus payment details — those
                            // stay local-only, P0-1).
                            val json = buildJsonObject {
                                put("offer_id", offer.offerId)
                                put("creator_peer_id", offer.creatorPeerId)
                                put("type", offer.type.name)
                                put("fiat_amount", offer.fiatAmount)
                                put("crypto_amount_sats", offer.cryptoAmountSats)
                                put("price_per_unit", offer.pricePerUnit)
                                put("fee_percent", offer.feePercent)
                                put("status", offer.status.name)
                                put("created_at", offer.createdAt)
                                offer.expiresAt?.let { put("expires_at", it) }
                            }.toString()
                            rnsTransport.sendOffer(env.fromPeerId, json)
                        }
                    }
                    "offer" -> {
                        offerRouter.ingestRnsOffer(env.data.toString(Charsets.UTF_8))
                    }
                }
            }
        }
        // Phase 3: offer-feed announces (neop2p/offers) — the digest carries
        // enough to render a card; the full JSON is fetched on demand.
        scope.launch {
            rnsTransport.offerAnnounces.collect { announce ->
                val digest = RnsOfferDigest.decode(announce.digestJson) ?: return@collect
                val offerId = RnsOfferDigest.offerIdOf(digest) ?: return@collect
                // Skip offers we already have (digest re-announce).
                if (offerDao.getOfferSync(offerId) != null) return@collect
                // Request the full offer over LXMF.
                rnsTransport.sendOfferRequest(announce.fromPeerId, offerId)
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
            // RNS first (Phase 2 dual-run): LXMF DIRECT delivery is the
            // preferred path once the peer has announced. Fall back to the
            // legacy hybrid transport (libp2p direct → WS relay) so nothing
            // regresses while both stacks are live.
            val rnsOk = rnsTransport.send(peerId, env.data, env.type).isSuccess
            if (rnsOk) return@drainFor true
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
        // RNS announce = peer online + fresh path: drain queued messages the
        // moment the peer's LXMF delivery destination is known (the legacy
        // peerRegistry may not have marked them online yet).
        scope.launch {
            rnsTransport.peerSeen.collect { peerId ->
                drainPending(peerId)
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
            nostrClient.offerStatusUpdates.collect { update ->
                val matchedPeerId = update.matchedPeerId
                if (matchedPeerId.isNullOrBlank()) return@collect
                val myPeerId = runCatching { identityManager.getOrCreateIdentity().peerId }
                    .getOrNull() ?: return@collect
                if (matchedPeerId.equals(myPeerId, ignoreCase = true)) return@collect
                // Only the offer CREATOR should be notified that their offer
                // was matched. The relay broadcasts kind:33336 to every
                // subscriber, so without this check every device watching the
                // offer (bystanders, the buyer's other devices) fires the
                // "Offer matched" notification too.
                val offer = offerDao.getOfferSync(update.offerId) ?: return@collect
                if (!offer.creator_peer_id.equals(myPeerId, ignoreCase = true)) return@collect
                // Guard against duplicate re-announcements: only notify once per
                // offer id, PERSISTENTLY (the relay replays matched events on
                // every reconnect/restart).
                val key = "match_${update.offerId}"
                if (alreadyNotified(key)) return@collect
                markNotified(key)
                notificationDispatcher.notifyOfferMatched(update.offerId, matchedPeerId)
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
                // Only notify when this deletion is actually NEW — replayed
                // NIP-09 events re-fire on every subscription otherwise.
                val key = "del_$deletedEventId"
                if (!alreadyNotified(key)) {
                    markNotified(key)
                    notificationDispatcher.notifyOfferDeleted(deletedEventId)
                }
            }
        }
    }

    /** Map escrow transitions to user-facing notifications. */
    private fun collectEscrowTransitions() {
        escrowTransitionJob?.cancel()
        escrowTransitionJob = scope.launch {
            escrowService.transitions.collect { t ->
                val mapped = when (t.status) {
                    "created", "funding" ->
                        context.getString(R.string.notif_escrow_created_title) to
                            context.getString(R.string.notif_escrow_created_body)
                    "funded" ->
                        context.getString(R.string.notif_escrow_funded_title) to
                            context.getString(R.string.notif_escrow_funded_body)
                    "signed" ->
                        context.getString(R.string.notif_escrow_signed_title) to
                            context.getString(R.string.notif_escrow_signed_body)
                    "paid", "payment_pending" ->
                        context.getString(R.string.notif_escrow_paid_title) to
                            context.getString(R.string.notif_escrow_paid_body)
                    "receipt_sent" ->
                        context.getString(R.string.notif_escrow_receipt_title) to
                            context.getString(R.string.notif_escrow_receipt_body)
                    "confirming" ->
                        context.getString(R.string.notif_escrow_confirming_title) to
                            context.getString(R.string.notif_escrow_confirming_body)
                    "payment_grace_reminder" ->
                        context.getString(R.string.notif_escrow_payment_grace_title) to
                            context.getString(R.string.notif_escrow_payment_grace_body)
                    "refund_grace_reminder" ->
                        context.getString(R.string.notif_escrow_refund_grace_title) to
                            context.getString(R.string.notif_escrow_refund_grace_body)
                    "released" ->
                        context.getString(R.string.notif_escrow_released_title) to
                            context.getString(R.string.notif_escrow_released_body)
                    "disputed" ->
                        context.getString(R.string.notif_escrow_disputed_title) to
                            context.getString(R.string.notif_escrow_disputed_body)
                    "resolving" ->
                        context.getString(R.string.notif_escrow_resolving_title) to
                            context.getString(R.string.notif_escrow_resolving_body)
                    "refunded" ->
                        context.getString(R.string.notif_escrow_refunded_title) to
                            context.getString(R.string.notif_escrow_refunded_body)
                    "cancelled" ->
                        context.getString(R.string.notif_escrow_cancelled_title) to
                            context.getString(R.string.notif_escrow_cancelled_body)
                    else -> null
                }
                mapped?.let { (title, message) ->
                    notificationDispatcher.notifyEscrow(t.escrowId, t.status, title, message)
                }
                // Auto-share: the moment the escrow is FUNDED the SELLER's bank
                // details go to the buyer over E2EE chat automatically (no
                // manual "Share payment details" tap needed). Only the seller
                // device fires; the buyer's device has no stored bank details
                // and must not send anything.
                if (t.status == "funded") {
                    runCatching {
                        val esc = escrowService.getEscrow(t.escrowId) ?: return@runCatching
                        val myPeerId = identityManager.getOrCreateIdentity().peerId
                        if (esc.sellerPeerId == myPeerId) {
                            val offer = offerDao.getOfferSync(esc.offerId)?.toDomain()
                            val details = offer?.paymentDetails.orEmpty()
                            if (details.isNotEmpty()) {
                                chatRouter.autoSharePaymentDetails(
                                    peerId = esc.buyerPeerId,
                                    offerId = esc.offerId,
                                    details = details
                                )
                            }
                        }
                    }.onFailure {
                        Log.w(TAG, "Auto-share payment details failed: ${it.message}")
                    }
                }
            }
        }
    }

    /**
     * Apply a dispute event (kind:33386) received from the relay: sync the
     * local escrow status to DISPUTED (idempotent) and notify. Fires for the
     * parties AND the arbitrator — the arbitrator learns a dispute exists
     * without any UI action from the parties.
     */
    /** Pure helper: can a local escrow be moved to DISPUTED via a remote 33386. */
    internal fun isDisputableStatus(local: com.neop2p.domain.model.Escrow?): Boolean {
        if (local == null) return false
        // Any non-terminal, not already DISPUTED/RESOLVING, may be disputed.
        // Terminal = RELEASED/REFUNDED/CANCELLED (same as EscrowRouter.TERMINAL)
        val s = local.status
        if (s == EscrowStatus.DISPUTED || s == EscrowStatus.RESOLVING) return false
        if (s == EscrowStatus.RELEASED || s == EscrowStatus.REFUNDED || s == EscrowStatus.CANCELLED) return false
        return true
    }

    private fun consumeDisputes() {
        disputeJob?.cancel()
        disputeJob = scope.launch {
            nostrClient.disputes.collect { obj ->
                applyDisputeEvent(obj)
            }
        }
    }

    /**
     * Apply a dispute event (kind:33386 / LXMF "dispute") received from the
     * relay or RNS: sync the local escrow status to DISPUTED (idempotent) and
     * notify. Fires for the parties AND the arbitrator — the arbitrator
     * learns a dispute exists without any UI action from the parties.
     */
    private suspend fun applyDisputeEvent(obj: kotlinx.serialization.json.JsonObject) {
        val escrowId = obj["escrow_id"]?.jsonPrimitive?.content ?: return
        try {
            // Persist for arbitrator durability (survives relay prune/reboot).
            // Upsert regardless of local escrow existence — arbitrator has no local escrow row.
            try {
                arbitratorDisputeDao.upsert(
                    ArbitratorDisputeEntity(
                        escrow_id = escrowId,
                        opened_by = obj["opened_by"]?.jsonPrimitive?.content ?: "",
                        reason = obj["reason"]?.jsonPrimitive?.content ?: "",
                        opened_at = obj["opened_at"]?.jsonPrimitive?.long ?: System.currentTimeMillis(),
                        redeem_script_hex = obj["redeem_script_hex"]?.jsonPrimitive?.content,
                        psbt_hex = obj["psbt_hex"]?.jsonPrimitive?.content,
                        refund_tx_hex = obj["refund_tx_hex"]?.jsonPrimitive?.content,
                        deposit_sats = obj["deposit_sats"]?.jsonPrimitive?.long,
                        funding_script_type = obj["funding_script_type"]?.jsonPrimitive?.content,
                        seller_refund_address = obj["seller_refund_address"]?.jsonPrimitive?.content,
                        received_at = System.currentTimeMillis(),
                        resolved = false
                    )
                )
                Log.d(TAG, "Persisted arbitrator dispute $escrowId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist arbitrator dispute $escrowId: ${e.message}")
            }
            val local = escrowService.getEscrow(escrowId)
            val openedBy = obj["opened_by"]?.jsonPrimitive?.content ?: ""
            // Auth: opener must be a party to the escrow (buyer or seller).
            // If we have no local row, we are the arbitrator without a row —
            // still notify but do not try to disputeEscrow (nothing to flip).
            if (local != null) {
                if (openedBy.isNotBlank() && openedBy != local.buyerPeerId && openedBy != local.sellerPeerId) {
                    Log.w(TAG, "Dropping dispute $escrowId: opener $openedBy not a party (isArb=${isArbitrator()} pub=${obj["opened_by"]})")
                } else if (isDisputableStatus(local)) {
                    escrowService.disputeEscrow(escrowId)
                }
            } else {
                Log.d(TAG, "Dispute $escrowId for unknown local escrow — arbitrator-only view, pub=${openedBy.take(12)}")
            }
            notificationDispatcher.notifyEscrow(
                escrowId, "disputed",
                context.getString(R.string.notif_dispute_opened_title),
                (obj["reason"]?.jsonPrimitive?.content)?.let {
                    context.getString(R.string.notif_dispute_opened_body, it)
                }
                    ?: context.getString(R.string.notif_dispute_opened_fallback)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply dispute event: ${e.message}")
        }
    }

    private fun isArbitrator(): Boolean = runCatching {
        identityManager.getArbitratorPubKeyHex().equals(NeoP2PConfig.ARBITRATOR_PUBKEY, ignoreCase = true)
    }.getOrDefault(false)

    /** Persist evidence (for arbitrator durability) + notify. */
    private fun consumeEvidence() {
        evidenceJob?.cancel()
        evidenceJob = scope.launch {
            nostrClient.evidence.collect { obj ->
                applyEvidenceEvent(obj)
            }
        }
    }

    /**
     * Apply a dispute-evidence event (kind:33387 / LXMF "evidence") received
     * from the relay or RNS: persist for arbitrator durability + notify.
     */
    private suspend fun applyEvidenceEvent(obj: kotlinx.serialization.json.JsonObject) {
        val escrowId = obj["escrow_id"]?.jsonPrimitive?.content ?: return
        val submitter = obj["submitter"]?.jsonPrimitive?.content ?: ""
        val description = obj["description"]?.jsonPrimitive?.content ?: ""
        val mimeType = obj["mime_type"]?.jsonPrimitive?.content ?: "image/jpeg"
        val imageBase64 = obj["image_base64"]?.jsonPrimitive?.content ?: ""
        // Persist for durability (arbitrator reboot survives relay prune).
        // Parties already store locally on submit; this covers the
        // counterparty/arbitrator who only sees the relay copy.
        if (imageBase64.isNotBlank()) {
            try {
                val bytes = runCatching {
                    android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
                }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    // Dedup: same submitter+escrow+description may replay; use UUID
                    // but guard against unbounded growth — DAO insert is idempotent
                    // per evidence_id, so each relay replay creates a new row.
                    // To avoid spam, check if an identical image already exists for this escrow.
                    val existing = disputeEvidenceDao.getEvidenceForEscrow(escrowId)
                    val isDuplicate = existing.any {
                        it.submitter_peer_id == submitter && it.description == description &&
                            it.image_data.size == bytes.size && it.image_data.contentEquals(bytes)
                    }
                    if (!isDuplicate) {
                        disputeEvidenceDao.insert(
                            DisputeEvidenceEntity(
                                evidence_id = java.util.UUID.randomUUID().toString(),
                                escrow_id = escrowId,
                                submitter_peer_id = submitter,
                                description = description,
                                mime_type = mimeType,
                                image_data = bytes,
                                submitted_at = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist evidence $escrowId: ${e.message}")
            }
        }
        // Only notify when THIS device is the arbitrator — regular
        // parties already see evidence locally on their own device.
        val isArb = runCatching {
            identityManager.getArbitratorPubKeyHex()
                .equals(NeoP2PConfig.ARBITRATOR_PUBKEY, ignoreCase = true)
        }.getOrDefault(false)
        if (isArb) {
            notificationDispatcher.notifyEscrow(
                escrowId, "evidence",
                context.getString(R.string.notif_evidence_title),
                context.getString(R.string.notif_evidence_body, submitter.take(8), escrowId)
            )
        }
    }

    /**
     * Apply an arbitration resolution (kind:33388 / LXMF "resolution") to the
     * local escrow so the winning party can broadcast the payout/refund with
     * the arbitrator's signature (2-of-3). Idempotent via
     * [EscrowService.storeArbitrationDecision].
     */
    private fun consumeResolutions() {
        resolutionJob?.cancel()
        resolutionJob = scope.launch {
            nostrClient.resolutions.collect { obj ->
                applyResolutionEvent(obj)
            }
        }
    }

    /**
     * Apply an arbitration resolution event received from the relay or RNS.
     */
    private suspend fun applyResolutionEvent(obj: kotlinx.serialization.json.JsonObject) {
        val escrowId = obj["escrow_id"]?.jsonPrimitive?.content ?: return
        // Durability: mark arbitrator dispute as resolved even before local escrow exists.
        try { arbitratorDisputeDao.markResolved(escrowId) } catch (_: Exception) {}
        val decisionStr = obj["decision"]?.jsonPrimitive?.content ?: return
        val sigHex = obj["arbitrator_sig_hex"]?.jsonPrimitive?.content ?: return
        val notes = obj["notes"]?.jsonPrimitive?.content
        val decision = when (decisionStr) {
            // New canonical names.
            "RELEASE_TO_BUYER" -> ResolutionDecision.RELEASE_TO_BUYER
            "REFUND_TO_SELLER" -> ResolutionDecision.REFUND_TO_SELLER
            // Backward compatibility: older kind:33388 events used the
            // old (inverted) names — map them to the same decisions so
            // already-published resolutions still apply.
            "RELEASE_TO_SELLER" -> ResolutionDecision.RELEASE_TO_BUYER
            "REFUND_TO_BUYER" -> ResolutionDecision.REFUND_TO_SELLER
            else -> return
        }
        try {
            // Persist the seller's refund address BEFORE applying the
            // decision: storeArbitrationDecision builds the refund tx
            // from escrow.refund_destination, and the address travels
            // in the resolution event (kind:33388).
            val refundAddr = obj["seller_refund_address"]?.jsonPrimitive?.content
            if (!refundAddr.isNullOrBlank()) {
                escrowService.persistRefundDestination(escrowId, refundAddr)
            }
            // The exact tx the arbitrator signed (kind:33388). When
            // present, the party broadcasts THIS tx — never a locally
            // rebuilt one (different fee rate ⇒ arbitrator sig would
            // not verify in multi-key deployments).
            val signedTxHex = obj["signed_tx_hex"]?.jsonPrimitive?.content
            val result = escrowService.storeArbitrationDecision(
                escrowId = escrowId,
                decision = decision,
                arbitratorSigHex = sigHex,
                notes = notes,
                signedTxHex = signedTxHex?.takeIf { it.isNotBlank() }
            )
            val updated = result.getOrNull()
            if (updated != null) {
                notificationDispatcher.notifyEscrow(
                    escrowId, updated.status.name.lowercase(),
                    context.getString(R.string.notif_resolved_title),
                    notes ?: context.getString(R.string.notif_resolved_body)
                )
            } else {
                val err = result.exceptionOrNull()?.message ?: "unknown"
                Log.w(TAG, "Failed to apply resolution $escrowId ($decision): $err")
                // Surface broadcast failure (e.g. bad-txns-inputs-missingorspent when escrow was never funded on-chain)
                // so parties know funds did not move and can retry after funding.
                notificationDispatcher.notifyEscrow(
                    escrowId, "resolution_failed",
                    context.getString(R.string.notif_resolved_title),
                    "Resolution failed: $err — escrow may be unfunded (funding tx not on-chain)"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply resolution: ${e.message}")
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
                // Retry pending dispute publishes (ack-gated 33386 that failed for lack of relay).
                retryPendingDisputes()
                // Auto-share retry: the seller's bank details must reach the
                // buyer for EVERY funded escrow, not only those that emitted a
                // live `funded` transition while both apps were online. After a
                // reinstall / restart / missed handshake the event is gone and
                // the buyer's escrow screen would show no payment info forever.
                // ChatRouter dedupes per offer on success, so re-scanning is a
                // cheap no-op once shared.
                retryPaymentDetailShares()
                // Lost MATCHED re-publish: a taker's claim that was persisted
                // locally but never reached the relay (kill before ack) must be
                // re-broadcast or the seller never sees the match.
                try {
                    val myId = identityManager.getOrCreateIdentity().peerId
                    offerRouter.republishLostClaims(myId)
                } catch (e: Exception) { Log.w(TAG, "Lost MATCHED republish failed: ${e.message}") }
                delay(ESCROW_SWEEP_INTERVAL_MS)
            }
        }
    }

    private suspend fun retryPendingDisputes() {
        try {
            val ids = pendingDisputeStore.allEscrowIds()
            if (ids.isEmpty()) {
                // No pending queue — still heal old disputes that were published with psbt=null (pre-fix 2026-09-01)
                healDisputePsbt()
                return
            }
            Log.d(TAG, "Retrying ${ids.size} pending dispute(s)")
            for (escrowId in ids) {
                val pending = pendingDisputeStore.load(escrowId) ?: continue
                // Skip if already DISPUTED locally (already healed via relay replay)
                val local = try { escrowService.getEscrow(escrowId) } catch (_: Exception) { null }
                if (local != null && local.status == EscrowStatus.DISPUTED) {
                    pendingDisputeStore.remove(escrowId)
                    continue
                }
                val result = nostrClient.publishDispute(
                    escrowId = pending.escrowId,
                    openedBy = pending.openedBy,
                    reason = pending.reason,
                    redeemScriptHex = pending.redeemScriptHex,
                    unsignedTxHex = pending.psbtHex,
                    refundTxHex = pending.refundTxHex,
                    depositSats = pending.depositSats,
                    fundingScriptType = pending.fundingScriptType,
                    sellerRefundAddress = pending.sellerRefundAddress
                )
                if (result.isSuccess) {
                    Log.i(TAG, "Retried pending dispute $escrowId succeeded")
                    pendingDisputeStore.remove(escrowId)
                    // Now flip locally
                    try { escrowService.disputeEscrow(escrowId) } catch (e: Exception) {
                        Log.w(TAG, "disputeEscrow after retry failed for $escrowId: ${e.message}")
                    }
                } else {
                    Log.w(TAG, "Retried pending dispute $escrowId still failing: ${result.exceptionOrNull()?.message}")
                }
            }
            // Also heal any old psbt-null disputes after pending batch
            healDisputePsbt()
        } catch (e: Exception) {
            Log.w(TAG, "retryPendingDisputes failed: ${e.message}")
        }
    }

    private suspend fun healDisputePsbt() {
        try {
            val disputes = arbitratorDisputeDao.getAll().filter { it.psbt_hex.isNullOrBlank() }
            if (disputes.isEmpty()) return
            Log.d(TAG, "Healing ${disputes.size} dispute(s) missing psbt")
            for (d in disputes) {
                val escrow = try { escrowService.getEscrow(d.escrow_id) } catch (_: Exception) { null } ?: continue
                // Only heal if escrow was actually funded on-chain (fundedAt set). Unfunded escrows (FUNDING, no fundingTxId or no confirmation) must remain refund-only / no-payout to avoid bad-txns-inputs-missingorspent.
                if (escrow.fundedAt == null && escrow.status != com.neop2p.domain.model.EscrowStatus.DISPUTED) {
                    Log.d(TAG, "Skip heal ${d.escrow_id} — escrow not funded (fundedAt null, status=${escrow.status})")
                    continue
                }
                if (escrow.fundingTxId.isNullOrBlank()) {
                    Log.d(TAG, "Skip heal ${d.escrow_id} — no fundingTxId")
                    continue
                }
                var psbt = escrow.psbtUnsigned?.toString(Charsets.UTF_8)
                if (psbt.isNullOrBlank()) {
                    val buyerAddr = escrow.buyerBtcAddress?.takeIf { it.isNotBlank() } ?: escrow.fundingAddress ?: continue
                    val gen = try {
                        escrowService.generatePayoutTransaction(escrow.escrowId, escrow.fundingTxId!!, escrow.fundingVout.toInt(), buyerAddr)
                    } catch (_: Exception) { continue }
                    if (gen.isSuccess) psbt = gen.getOrNull()
                }
                if (psbt.isNullOrBlank()) continue
                arbitratorDisputeDao.upsert(d.copy(psbt_hex = psbt))
                Log.i(TAG, "Healed dispute ${d.escrow_id} with psbt len=${psbt.length}")
                // Re-publish 33386 with corrected psbt so arbitrator sees both buttons
                try {
                    nostrClient.publishDispute(
                        escrowId = d.escrow_id,
                        openedBy = d.opened_by,
                        reason = d.reason,
                        redeemScriptHex = d.redeem_script_hex,
                        unsignedTxHex = psbt,
                        refundTxHex = d.refund_tx_hex,
                        depositSats = d.deposit_sats,
                        fundingScriptType = d.funding_script_type,
                        sellerRefundAddress = d.seller_refund_address
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Heal republish failed for ${d.escrow_id}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "healDisputePsbt failed: ${e.message}")
        }
    }

    /** For every FUNDED+ escrow where THIS device is the seller, re-attempt the
     *  auto-share of bank details (idempotent — ChatRouter dedupes on success). */
    private suspend fun retryPaymentDetailShares() {
        try {
            val myPeerId = identityManager.getOrCreateIdentity().peerId
            val shareable = setOf(
                EscrowStatus.FUNDED.name,
                EscrowStatus.PAYMENT_PENDING.name,
                EscrowStatus.RECEIPT_SENT.name,
                EscrowStatus.CONFIRMING.name
            )
            for (escrow in escrowService.getAllEscrows()) {
                if (escrow.sellerPeerId != myPeerId) continue
                if (escrow.status.name !in shareable) continue
                val offer = offerDao.getOfferSync(escrow.offerId)?.toDomain() ?: continue
                val details = offer.paymentDetails.orEmpty()
                if (details.isEmpty()) {
                    Log.d(TAG, "Retry share: escrow ${escrow.escrowId} has NO payment details on offer ${escrow.offerId}")
                    continue
                }
                chatRouter.resendPaymentDetails(escrow.buyerPeerId, escrow.offerId, details)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Payment-details retry sweep failed: ${e.message}")
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
        disputeJob?.cancel()
        disputeJob = null
        evidenceJob?.cancel()
        evidenceJob = null
        resolutionJob?.cancel()
        resolutionJob = null
        nostrClient.disconnect()
        p2pTransport.stop()
        rnsTransport.stop()
    }

    companion object {
        private const val TAG = "P2POrchestrator"
        private const val ESCROW_SWEEP_INTERVAL_MS = 60_000L
    }
}
