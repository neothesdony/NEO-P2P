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
import com.neop2p.data.p2p.routing.OfferFeedGate
import com.neop2p.data.p2p.store.PeerRegistry
import com.neop2p.data.reputation.ReputationSystem
import com.neop2p.domain.model.EscrowStatus
import com.neop2p.domain.model.OfferStatus
import com.neop2p.domain.model.ResolutionDecision
import com.neop2p.service.AppForegroundTracker
import com.neop2p.service.NotificationDispatcher
import com.neop2p.service.WalletWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level coordinator for the P2P message-routing pipeline.
 *
 * Owns the lifecycle of the RNS/LXMF transport, identity, reputation, and
 * router components, and dispatches inbound [AppMessage]s to the correct
 * handler. [start] is idempotent; [stop] tears down the network-facing
 * components.
 *
 * Phase 4: RNS/LXMF is the ONLY transport — libp2p, the WS relay, Nostr, and
 * WebRTC were removed. All inbound traffic arrives via [RnsTransport]:
 *   - EnvelopeCodec AppMessages (pre-key handshake, chat) — type = LXMF title
 *   - LXMF signaling (offer_status / escrow_status / dispute / evidence /
 *     resolution / offer_request / offer) — raw JSON in FIELD_CUSTOM_DATA
 *   - Offer-feed announces (neop2p/offers) — compact digests
 */
@Singleton
class P2POrchestrator @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val identityManager: IdentityManager,
    private val rnsTransport: RnsTransport,
    private val signal: SignalProtocol,
    private val reputation: ReputationSystem,
    private val peerRegistry: PeerRegistry,
    private val queue: OfflineQueue,
    private val chatRouter: ChatRouter,
    private val offerRouter: OfferRouter,
    private val escrowRouter: EscrowRouter,
    private val escrowService: EscrowService,
    private val offerDao: OfferDao,
    private val deletedOfferStore: DeletedOfferStore,
    private val notificationDispatcher: NotificationDispatcher,
    private val appForegroundTracker: AppForegroundTracker,
    private val walletWatcher: WalletWatcher,
    private val disputeEvidenceDao: DisputeEvidenceDao,
    private val arbitratorDisputeDao: ArbitratorDisputeDao,
    private val pendingDisputeStore: com.neop2p.data.local.PendingDisputeStore,
    private val pendingArbitrationStore: com.neop2p.data.local.PendingArbitrationStore,
    private val scope: CoroutineScope
) {
    @Volatile private var running = false
    @Volatile private var inboundJob: Job? = null
    @Volatile private var notifyInboundJob: Job? = null
    @Volatile private var escrowTransitionJob: Job? = null
    @Volatile private var escrowSweepJob: Job? = null
    @Volatile private var offerReannounceJob: Job? = null

    private val _transportReady = MutableStateFlow(false)
    val transportReady: StateFlow<Boolean> = _transportReady.asStateFlow()

    @Volatile private var lastTransportStartFailure: Throwable? = null
    val transportStartFailure: Throwable? get() = lastTransportStartFailure

    private fun updateTransportReady() {
        _transportReady.value = rnsTransport.state.value.isRunning
    }

    /** I5: evidence images are capped at 60KB at the UI; 80KB base64 ≈ 60KB binary. */
    private val MAX_EVIDENCE_BASE64_CHARS = 80 * 1024

    /**
     * Digest commitments seen on the offer feed, keyed by offer id, awaiting
     * the LXMF-fetched offer JSON. In-memory only: a missed fetch is simply
     * re-triggered by the next 20s re-announce. The value carries the
     * announcing peer so pull-to-refresh can re-request digests whose fetch
     * failed (the peer that announced it is the one that serves it).
     */
    private val pendingOfferDigests =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.serialization.json.JsonObject>()

    /**
     * The peer that announced each pending digest (offer id -> peerId), so
     * pull-to-refresh can re-request a digest whose fetch failed. Removed
     * together with the digest when the offer lands.
     */
    private val pendingOfferPeers = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** True while the orchestrator (and thus the RNS transport) is running. */
    fun isRunning(): Boolean = running

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
            // pipeline (transport, identity) can still come up.
            signal.initialize().onFailure {
                Log.w(TAG, "Signal init failed (continuing): ${it.message}")
            }
            lastTransportStartFailure = null
            rnsTransport.start().onFailure {
                Log.w(TAG, "RNS start failed: ${it.message}")
                lastTransportStartFailure = it
            }
            updateTransportReady()
            reputation.initialize()
            // Fix 2: scan for stale escrows on startup so a FUNDED-but-stalled
            // escrow auto-refunds (and an unfunded one auto-cancels). Idempotent.
            escrowService.initialize()
            offerRouter.startListening(scope)
            escrowRouter.startListening(scope)
            listenInbound()
            launchPeerDrain()
            notifyInboundChat()
            collectEscrowTransitions()
            sweepStaleEscrows()
            rehydrateOfferReannounce()
            walletWatcher.start(scope)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Orchestrator start failed", e)
            running = false
            updateTransportReady()
            Result.failure(e)
        }
    }

    private fun listenInbound() {
        inboundJob?.cancel()
        inboundJob = scope.launch {
            rnsTransport.incomingMessages.collect { env ->
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
        // LXMF signaling (offer_status / escrow_status / dispute / evidence /
        // resolution / offer_request / offer) arrives as raw JSON in the LXMF
        // title + FIELD_CUSTOM_DATA — NOT as EnvelopeCodec AppMessages. Route
        // them to the same handlers the (removed) Nostr collectors used so the
        // RNS path is the single source of truth.
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
                    "offer_delete" -> {
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                env.data.toString(Charsets.UTF_8)
                            ).jsonObject
                        }.getOrNull() ?: return@collect
                        offerRouter.applyOfferDelete(
                            offerId = obj["offer_id"]?.jsonPrimitive?.content ?: return@collect,
                            fromPeerId = env.fromPeerId
                        )
                    }
                    "attestation" -> {
                        val json = env.data.toString(Charsets.UTF_8)
                        reputation.processAttestation(json, env.fromPeerId)
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
                        applyDisputeEvent(obj, env.fromPeerId)
                    }
                    "evidence" -> {
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                env.data.toString(Charsets.UTF_8)
                            ).jsonObject
                        }.getOrNull() ?: return@collect
                        applyEvidenceEvent(obj, env.fromPeerId)
                    }
                    "resolution" -> {
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                env.data.toString(Charsets.UTF_8)
                            ).jsonObject
                        }.getOrNull() ?: return@collect
                        applyResolutionEvent(obj, env.fromPeerId)
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
                            // Serve the canonical public subset (G1): the same
                            // JSON the digest commitment hashes, so the
                            // requester can verify it. Payment details and the
                            // BTC receive address stay local-only (P0-1).
                            val nickname = runCatching {
                                identityManager.getOrCreateIdentity().nickname
                            }.getOrDefault("")
                            val json = RnsOfferDigest.canonicalJson(offer, nickname)
                            rnsTransport.sendOffer(env.fromPeerId, json)
                        }
                    }
                    "offer" -> {
                        val offerJson = env.data.toString(Charsets.UTF_8)
                        val offerId = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(offerJson)
                                .jsonObject["offer_id"]?.jsonPrimitive?.content
                        }.getOrNull()
                        // G1: only ingest an offer whose served JSON matches
                        // the commitment seen in the announce — a peer cannot
                        // announce one offer and serve a different one.
                        val digest = offerId?.let { pendingOfferDigests.remove(it) }
                        if (offerId != null) pendingOfferPeers.remove(offerId)
                        if (digest != null && !RnsOfferDigest.verify(offerJson, digest)) {
                            Log.w(TAG, "Offer $offerId failed digest commitment — dropping")
                            return@collect
                        }
                        offerRouter.ingestRnsOffer(offerJson)
                    }
                }
            }
        }
        // Offer-feed announces (neop2p/offers) — the digest is a commitment
        // (offer id + hash); the full public subset is fetched on demand over
        // encrypted LXMF and verified against the commitment before ingest.
        scope.launch {
            rnsTransport.offerAnnounces.collect { announce ->
                val digest = RnsOfferDigest.decode(announce.digestJson) ?: return@collect
                val offerId = RnsOfferDigest.offerIdOf(digest) ?: return@collect
                // 2026-09-02 (3rd-device convergence): a TERMINAL tombstone
                // digest transitions a held row to the terminal status
                // without fetching (the digest carries no status — the
                // creator's escrow lifecycle is the authority). Only the
                // offer CREATOR's device ever announces a tombstone for their
                // own offer, so a stranger's spoofed tombstone can never kill
                // someone else's offer. Never creates or resurrects a row.
                if (RnsOfferDigest.isTombstone(digest)) {
                    val existing = offerDao.getOfferSync(offerId)
                    if (existing != null &&
                        announce.fromPeerId == existing.creator_peer_id &&
                        OfferFeedGate.acceptTombstone(existing.status)
                    ) {
                        // The tombstone carries no terminal-status flavor
                        // (G1 — the digest never leaks status). COMPLETED and
                        // CANCELLED are both terminal: they leave the feed and
                        // disable Accept identically. COMPLETED is the
                        // canonical choice — the tombstone is only announced
                        // for a released/refunded trade's offer, and the
                        // receiver's UI treats both as terminal.
                        offerDao.updateStatus(offerId, OfferStatus.COMPLETED.name)
                        Log.i(TAG, "Applied terminal tombstone for $offerId (was ${existing.status})")
                    }
                    return@collect
                }
                val existing = offerDao.getOfferSync(offerId)
                if (existing != null) {
                    // Skip offers we already have UNLESS the commitment hash
                    // changed (the offer was edited or its status changed —
                    // e.g. OPEN → MATCHED/COMPLETED on the creator's side).
                    // Only the CREATOR's announce is trusted to move a held
                    // row — a stranger's digest could otherwise fabricate a
                    // hash mismatch and make us refetch a stale copy. The
                    // stored hash is computed with the creator's nickname
                    // (OfferRouter.storedDigestHash) so the comparison is
                    // exact; a locked identity yields null → no refetch (the
                    // tombstone path covers terminal convergence instead).
                    if (announce.fromPeerId != existing.creator_peer_id) return@collect
                    val storedHash = offerRouter.storedDigestHash(existing)
                    if (!OfferFeedGate.needsReFetch(existing.status, storedHash, digest)) return@collect
                    Log.i(TAG, "Digest hash changed for held offer $offerId (${existing.status}) — refetching")
                    pendingOfferDigests[offerId] = digest
                    pendingOfferPeers[offerId] = announce.fromPeerId
                    rnsTransport.sendOfferRequest(announce.fromPeerId, offerId)
                    return@collect
                }
                // Fresh offer — remember the commitment so the fetched offer
                // can be verified.
                pendingOfferDigests[offerId] = digest
                // The announcing peer rides along so pull-to-refresh can
                // re-request a digest whose fetch failed (the announcer is
                // the one that serves the full offer).
                pendingOfferPeers[offerId] = announce.fromPeerId
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
            rnsTransport.send(peerId, env.data, env.type).isSuccess
        }
    }

    private fun launchPeerDrain() {
        scope.launch {
            // Drain queued messages for any peer that comes online. `authenticated`
            // is advisory only (sessions are bound by identity checks), so drain
            // for every online peer.
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
        // RNS announce = peer online + fresh path: record presence (so the
        // chat chip and the escrow relay-gate see a live peer) AND drain
        // queued messages the moment the peer's LXMF delivery destination is
        // known. Presence is RELAYED (announces ride the VPS transport node;
        // the fork never marks authenticated=true — session identity binding
        // is the actual trust anchor).
        scope.launch {
            rnsTransport.peerSeen.collect { peerId ->
                peerRegistry.recordPeerSeen(peerId)
                drainPending(peerId)
            }
        }
    }

    /**
     * Notify the user of inbound chat messages received via the E2EE pipeline.
     *
     * Consumes [ChatRouter.incomingChats] — which carries the REAL offer id.
     * Only posts while the app is NOT in the foreground; the chat screen cancels
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

    /**
     * Apply a dispute event (LXMF "dispute") received over RNS: sync the local
     * escrow status to DISPUTED (idempotent) and notify. Fires for the parties
     * AND the arbitrator — the arbitrator learns a dispute exists without any
     * UI action from the parties.
     */
    private suspend fun applyDisputeEvent(obj: kotlinx.serialization.json.JsonObject, fromPeerId: String) {
        val escrowId = obj["escrow_id"]?.jsonPrimitive?.content ?: return
        try {
            // Re-delivery guard (2026-09-02): the LXMF router retries a
            // DIRECT message until it gets a delivery receipt, and the
            // party's 60s sweep re-sends pending disputes — so the same
            // dispute can arrive many times. Once the arbitrator resolved
            // it, every re-delivery is stale: skip processing AND the
            // notification (the feed row is already marked resolved).
            val alreadyResolved = runCatching {
                arbitratorDisputeDao.getById(escrowId)?.resolved == true
            }.getOrDefault(false)
            if (!shouldProcessDispute(alreadyResolved)) {
                Log.d(TAG, "Dispute $escrowId already resolved — ignoring re-delivery")
                return
            }
            // First-delivery flag: notify only when the dispute is NEW —
            // re-deliveries (router retry / sweep re-send) must not re-alert.
            val known = runCatching {
                arbitratorDisputeDao.getById(escrowId) != null
            }.getOrDefault(false)
            val openedBy = obj["opened_by"]?.jsonPrimitive?.content ?: ""
            val buyerPeerId = obj["buyer_peer_id"]?.jsonPrimitive?.content
            val sellerPeerId = obj["seller_peer_id"]?.jsonPrimitive?.content
            val local = escrowService.getEscrow(escrowId)
            // Auth (2026-09-02): the sender must be a party to the escrow —
            // the opener's peerId, or (when the event carries them) the buyer
            // or seller. A stranger cannot open a dispute on someone else's
            // escrow or spam the arbitrator's feed. The arbitrator (no local
            // row) relies on the carried party ids; a dispute carrying NEITHER
            // the opener nor any party id is dropped.
            val senderIsParty = openedBy == fromPeerId ||
                buyerPeerId == fromPeerId || sellerPeerId == fromPeerId
            if (!senderIsParty) {
                Log.w(TAG, "Dropping dispute $escrowId: sender $fromPeerId is not a party (openedBy=$openedBy)")
                return
            }
            // Persist for arbitrator durability (survives reboot).
            // Upsert regardless of local escrow existence — arbitrator has no local escrow row.
            try {
                arbitratorDisputeDao.upsert(
                    ArbitratorDisputeEntity(
                        escrow_id = escrowId,
                        opened_by = openedBy,
                        reason = obj["reason"]?.jsonPrimitive?.content ?: "",
                        opened_at = obj["opened_at"]?.jsonPrimitive?.long ?: System.currentTimeMillis(),
                        redeem_script_hex = obj["redeem_script_hex"]?.jsonPrimitive?.content,
                        psbt_hex = obj["psbt_hex"]?.jsonPrimitive?.content,
                        refund_tx_hex = obj["refund_tx_hex"]?.jsonPrimitive?.content,
                        deposit_sats = obj["deposit_sats"]?.jsonPrimitive?.long,
                        funding_script_type = obj["funding_script_type"]?.jsonPrimitive?.content,
                        seller_refund_address = obj["seller_refund_address"]?.jsonPrimitive?.content,
                        buyer_peer_id = buyerPeerId,
                        seller_peer_id = sellerPeerId,
                        received_at = System.currentTimeMillis(),
                        resolved = false
                    )
                )
                Log.d(TAG, "Persisted arbitrator dispute $escrowId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist arbitrator dispute $escrowId: ${e.message}")
            }
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
            // Notify only on the FIRST delivery of a dispute — re-deliveries
            // (LXMF router retry, 60s sweep re-send) must not re-alert.
            if (!known) {
                notificationDispatcher.notifyEscrow(
                    escrowId, "disputed",
                    context.getString(R.string.notif_dispute_opened_title),
                    (obj["reason"]?.jsonPrimitive?.content)?.let {
                        context.getString(R.string.notif_dispute_opened_body, it)
                    }
                        ?: context.getString(R.string.notif_dispute_opened_fallback)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply dispute event: ${e.message}")
        }
    }

    private fun isArbitrator(): Boolean = runCatching {
        identityManager.getArbitratorPubKeyHex().equals(NeoP2PConfig.ARBITRATOR_PUBKEY, ignoreCase = true)
    }.getOrDefault(false)

    /**
     * Apply a dispute-evidence event (LXMF "evidence") received over RNS:
     * persist for arbitrator durability + notify.
     */
    private suspend fun applyEvidenceEvent(obj: kotlinx.serialization.json.JsonObject, fromPeerId: String) {
        val escrowId = obj["escrow_id"]?.jsonPrimitive?.content ?: return
        // Re-delivery guard (2026-09-02): the LXMF router retries DIRECT
        // messages until a delivery receipt, so the same evidence can arrive
        // many times. Once the dispute is resolved, evidence is stale — drop
        // it (no re-persist, no re-notify).
        val alreadyResolved = runCatching {
            arbitratorDisputeDao.getById(escrowId)?.resolved == true
        }.getOrDefault(false)
        if (!shouldProcessDispute(alreadyResolved)) {
            Log.d(TAG, "Evidence for $escrowId after resolution — ignoring re-delivery")
            return
        }
        val submitter = obj["submitter"]?.jsonPrimitive?.content ?: ""
        val description = obj["description"]?.jsonPrimitive?.content ?: ""
        val mimeType = obj["mime_type"]?.jsonPrimitive?.content ?: "image/jpeg"
        val imageBase64 = obj["image_base64"]?.jsonPrimitive?.content ?: ""
        // Auth (2026-09-02): the submitter must be the sender — a stranger
        // cannot inject evidence into someone else's dispute. The submitter
        // field is advisory (display only); the sender identity is the gate.
        if (submitter != fromPeerId) {
            Log.w(TAG, "Dropping evidence for $escrowId: submitter $submitter != sender $fromPeerId")
            return
        }
        // I5: cap inbound evidence — the UI caps at 60KB, so anything far
        // beyond that is hostile. Check BEFORE decoding (base64 inflates 4/3).
        if (imageBase64.length > MAX_EVIDENCE_BASE64_CHARS) {
            Log.w(TAG, "Dropping oversized evidence for $escrowId (${imageBase64.length} base64 chars)")
            return
        }
        // Persist for durability (arbitrator reboot survives).
        // Parties already store locally on submit; this covers the
        // counterparty/arbitrator who only sees the RNS copy.
        // Dedup: same submitter+escrow+description may replay; use UUID
        // but guard against unbounded growth — DAO insert is idempotent
        // per evidence_id, so each replay creates a new row.
        // To avoid spam, check if an identical image already exists for this escrow.
        var isDuplicate = false
        if (imageBase64.isNotBlank()) {
            try {
                val bytes = runCatching {
                    android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
                }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    val existing = disputeEvidenceDao.getEvidenceForEscrow(escrowId)
                    isDuplicate = existing.any {
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
        // First-delivery only: a content-duplicate re-delivery (LXMF router
        // retry / sweep re-send) must not re-alert.
        if (isArbitrator() && !isDuplicate) {
            notificationDispatcher.notifyEscrow(
                escrowId, "evidence",
                context.getString(R.string.notif_evidence_title),
                context.getString(R.string.notif_evidence_body, submitter.take(8), escrowId)
            )
        }
    }

    /**
     * Apply an arbitration resolution (LXMF "resolution") to the local escrow
     * so the winning party can broadcast the payout/refund with the
     * arbitrator's signature (2-of-3). Idempotent via
     * [EscrowService.storeArbitrationDecision].
     */
    private suspend fun applyResolutionEvent(obj: kotlinx.serialization.json.JsonObject, fromPeerId: String) {
        val escrowId = obj["escrow_id"]?.jsonPrimitive?.content ?: return
        val decisionStr = obj["decision"]?.jsonPrimitive?.content ?: return
        val sigHex = obj["arbitrator_sig_hex"]?.jsonPrimitive?.content ?: return
        val notes = obj["notes"]?.jsonPrimitive?.content
        val decision = when (decisionStr) {
            // New canonical names.
            "RELEASE_TO_BUYER" -> ResolutionDecision.RELEASE_TO_BUYER
            "REFUND_TO_SELLER" -> ResolutionDecision.REFUND_TO_SELLER
            // Backward compatibility: older LXMF resolution message events used the
            // old (inverted) names — map them to the same decisions so
            // already-published resolutions still apply.
            "RELEASE_TO_SELLER" -> ResolutionDecision.RELEASE_TO_BUYER
            "REFUND_TO_BUYER" -> ResolutionDecision.REFUND_TO_SELLER
            else -> return
        }
        // Auth (2026-09-02): only the arbitrator may publish a resolution.
        // The sender's peerId must be the configured arbitrator peer — a
        // stranger's "resolution" must not mark the feed resolved (which
        // previously hid the dispute AND dropped the legitimate pending
        // resolution from the retry sweep).
        if (NeoP2PConfig.ARBITRATOR_PEER_ID.isNotBlank() &&
            fromPeerId != NeoP2PConfig.ARBITRATOR_PEER_ID
        ) {
            Log.w(TAG, "Dropping resolution $escrowId: sender $fromPeerId is not the arbitrator")
            return
        }
        // Verify the arbitrator's signature BEFORE marking the feed resolved
        // (2026-09-02): a garbage sig must not hide the dispute from the
        // actionable feed or drop the legitimate pending resolution.
        val sigValid = runCatching {
            val dispute = arbitratorDisputeDao.getById(escrowId)
            val redeemHex = dispute?.redeem_script_hex
                ?: escrowService.getEscrow(escrowId)?.redeemScriptHex
            if (redeemHex.isNullOrBlank()) {
                Log.w(TAG, "Resolution $escrowId: no redeem script to verify against")
                false
            } else {
                escrowService.verifyArbitratorSignature(
                    txHex = obj["signed_tx_hex"]?.jsonPrimitive?.content
                        ?: dispute?.psbt_hex
                        ?: escrowService.getEscrow(escrowId)?.psbtUnsigned?.toString(Charsets.UTF_8),
                    redeemScriptHex = redeemHex,
                    arbitratorSigHex = sigHex,
                    depositSats = dispute?.deposit_sats
                        ?: escrowService.getEscrow(escrowId)?.fundedAmountSats
                        ?: escrowService.getEscrow(escrowId)?.depositAmountSats,
                    fundingScriptType = dispute?.funding_script_type
                        ?: escrowService.getEscrow(escrowId)?.fundingScriptType?.name
                )
            }
        }.getOrDefault(false)
        if (!sigValid) {
            Log.w(TAG, "Dropping resolution $escrowId: arbitrator signature failed verification")
            return
        }
        // First-delivery guard (2026-09-02): the LXMF router retries DIRECT
        // messages until a delivery receipt, so the same resolution can
        // arrive many times. storeArbitrationDecision is idempotent (keeps
        // the first decision), but the notification must fire only once.
        // Read BEFORE markResolved below — otherwise every delivery looks
        // like a re-delivery and the notification never fires.
        val wasResolved = runCatching {
            arbitratorDisputeDao.getById(escrowId)?.resolved == true
        }.getOrDefault(false)
        // Durability: mark arbitrator dispute as resolved even before local escrow exists.
        try { arbitratorDisputeDao.markResolved(escrowId) } catch (_: Exception) {}
        try {
            // Persist the seller's refund address BEFORE applying the
            // decision: storeArbitrationDecision builds the refund tx
            // from escrow.refund_destination, and the address travels
            // in the resolution event.
            val refundAddr = obj["seller_refund_address"]?.jsonPrimitive?.content
            if (!refundAddr.isNullOrBlank()) {
                escrowService.persistRefundDestination(escrowId, refundAddr)
            }
            // The exact tx the arbitrator signed. When present, the party
            // broadcasts THIS tx — never a locally rebuilt one (different fee
            // rate ⇒ arbitrator sig would not verify in multi-key deployments).
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
                // Notify only on the FIRST application — re-deliveries of the
                // same resolution are silent (the escrow is already terminal).
                if (!wasResolved) {
                    notificationDispatcher.notifyEscrow(
                        escrowId, updated.status.name.lowercase(),
                        context.getString(R.string.notif_resolved_title),
                        notes ?: context.getString(R.string.notif_resolved_body)
                    )
                }
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
                // Transport self-heal: if the RNS transport failed to start
                // (e.g. identity locked behind device auth at app launch),
                // retry every sweep — the user may have unlocked the phone
                // since. Idempotent: start() is a no-op once the session is up.
                if (!rnsTransport.state.value.isRunning) {
                    rnsTransport.start().onFailure {
                        Log.w(TAG, "Transport retry failed: ${it.message}")
                        lastTransportStartFailure = it
                    }
                }
                updateTransportReady()
                escrowService.expireStaleEscrows()
                // Retry pending dispute publishes (ack-gated 33386 that failed
                // for lack of relay — now delivered over LXMF instead).
                retryPendingDisputes()
                // Slice 3: durable retry for evidence/resolution deliveries
                // that failed at send time (a kill before send or a long-offline
                // target). Idempotent: evidence dedups by content on ingest,
                // resolutions skip escrows already marked resolved.
                retryPendingArbitration()
                // Auto-share retry: the seller's bank details must reach the
                // buyer for EVERY funded escrow, not only those that emitted a
                // live `funded` transition while both apps were online. After a
                // reinstall / restart / missed handshake the event is gone and
                // the buyer's escrow screen would show no payment info forever.
                // ChatRouter dedupes per offer on success, so re-scanning is a
                // cheap no-op once shared.
                retryPaymentDetailShares()
                // Lost MATCHED re-publish: a taker's claim that was persisted
                // locally but never delivered (kill before send) must be
                // re-broadcast or the seller never sees the match.
                try {
                    val myId = identityManager.getOrCreateIdentity().peerId
                    offerRouter.republishLostClaims(myId)
                } catch (e: Exception) { Log.w(TAG, "Lost MATCHED republish failed: ${e.message}") }
                delay(ESCROW_SWEEP_INTERVAL_MS)
            }
        }
    }

    /**
     * Keep the paced offer-feed re-announce sets in sync with the durable
     * offer table. Seeded on start (cold-start rediscovery of a seller's open
     * offers) and re-synced every sweep interval so edited / matched /
     * terminal offers are added or dropped. The digest keying is by offer id
     * (the commitment carries it), so an edit just replaces the digest for
     * the same key. Pacing happens in RnsSession (one announce per tick) —
     * this loop only maintains the sets.
     *
     * 2026-09-02 (3rd-device convergence): the loop ALSO seeds the terminal-
     * tombstone set from COMPLETED/CANCELLED offers, so non-participant peers
     * keep converging on the terminal status long after the trade finished
     * (and across app restarts). The sets are disjoint by construction: a
     * status is either live (OPEN/PAUSED/MATCHED/ESCROWED — the digest embeds
     * the status so a status change changes the hash, and locked offers stay
     * discoverable so takers see "taken") or terminal (COMPLETED/CANCELLED →
     * tombstone); offer ids are timestamp-based and never reused.
     */
    private fun rehydrateOfferReannounce() {
        offerReannounceJob?.cancel()
        offerReannounceJob = scope.launch {
            while (isActive) {
                val (liveDigests, tombstones) = try {
                    val identity = identityManager.getOrCreateIdentity()
                    val myOffers = offerDao.getAllOffersSync()
                        .filter { it.creator_peer_id == identity.peerId }
                    val digests = myOffers
                        .filter { it.status == "OPEN" || it.status == "PAUSED" || it.status == "MATCHED" || it.status == "ESCROWED" }
                        .associate { offer ->
                            offer.offer_id to RnsOfferDigest.encode(offer.toDomain(), identity.nickname)
                        }
                    val tombstones = myOffers
                        .filter { it.status == "COMPLETED" || it.status == "CANCELLED" }
                        .associate { offer ->
                            offer.offer_id to RnsOfferDigest.encodeTombstone(offer.offer_id)
                        }
                    digests to tombstones
                } catch (e: Exception) {
                    Log.w(TAG, "Offer re-announce rehydrate failed: ${e.message}")
                    emptyMap<String, String>() to emptyMap<String, String>()
                }
                rnsTransport.setOpenOfferDigests(liveDigests)
                rnsTransport.setTerminalTombstones(tombstones)
                delay(ESCROW_SWEEP_INTERVAL_MS)
            }
        }
    }

    /**
     * Pull-to-refresh (HomeScreen): re-announce our own open offers NOW so
     * peers re-fetch them (rate-capped in RnsSession), and re-request any
     * digest we saw but never fetched (the announcing peer serves it). The
     * paced loop + announce handler cover the steady state; this is the
     * user-visible "refresh the market" gesture.
     */
    suspend fun refreshFeed() {
        try {
            rnsTransport.refreshFeed()
            // Re-request digests whose LXMF fetch failed or never arrived.
            for ((offerId, peerId) in pendingOfferPeers) {
                rnsTransport.sendOfferRequest(peerId, offerId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshFeed failed: ${e.message}")
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
                val local = try { escrowService.getEscrow(escrowId) } catch (_: Exception) { null }
                // v23 (2026-09-02): per-target tracking. A row with explicit
                // targets (auto-dispute, or a partial delivery) retries ONLY
                // the undelivered targets and is dropped when all ack — even
                // when the local row is already DISPUTED (the auto-dispute
                // case: the local flip happened, but the arbitrator never
                // learned about it).
                if (pending.targets.isNotEmpty()) {
                    val remaining = pending.targets.filter { target ->
                        val ok = publishDisputeToTarget(pending, local, target).isSuccess
                        if (!ok) Log.w(TAG, "Pending dispute $escrowId still failing to $target")
                        ok
                    }
                    if (remaining.isEmpty()) {
                        pendingDisputeStore.remove(escrowId)
                        Log.i(TAG, "Retried pending dispute $escrowId delivered to all targets")
                    } else if (remaining.size != pending.targets.size) {
                        pendingDisputeStore.save(pending.copy(targets = remaining))
                    }
                    continue
                }
                // Legacy row (no targets): skip if already DISPUTED locally
                // (already healed via replay).
                if (local != null && local.status == EscrowStatus.DISPUTED) {
                    pendingDisputeStore.remove(escrowId)
                    continue
                }
                val result = publishDisputeRns(pending, local)
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

    /** Deliver a dispute to ONE target over LXMF (v23 per-target retry). */
    private suspend fun publishDisputeToTarget(
        pending: com.neop2p.data.local.PendingDisputeStore.PendingDispute,
        local: com.neop2p.domain.model.Escrow?,
        target: String
    ): Result<Unit> {
        val fields = buildMap {
            pending.redeemScriptHex?.let { put("redeem_script_hex", it) }
            pending.psbtHex?.let { put("psbt_hex", it) }
            pending.refundTxHex?.let { put("refund_tx_hex", it) }
            pending.depositSats?.let { put("deposit_sats", it.toString()) }
            pending.fundingScriptType?.let { put("funding_script_type", it) }
            pending.sellerRefundAddress?.let { put("seller_refund_address", it) }
            local?.let {
                put("buyer_peer_id", it.buyerPeerId)
                put("seller_peer_id", it.sellerPeerId)
            }
        }
        return rnsTransport.sendDispute(
            toPeerId = target,
            escrowId = pending.escrowId,
            openedBy = pending.openedBy,
            reason = pending.reason,
            fields = fields
        )
    }

    /**
     * Deliver a dispute over LXMF to the counterparty + arbitrator (RNS path).
     * Mirrors the removed Nostr LXMF dispute message publish.
     */
    private suspend fun publishDisputeRns(
        pending: com.neop2p.data.local.PendingDisputeStore.PendingDispute,
        local: com.neop2p.domain.model.Escrow?
    ): Result<Unit> {
        val fields = buildMap {
            pending.redeemScriptHex?.let { put("redeem_script_hex", it) }
            pending.psbtHex?.let { put("psbt_hex", it) }
            pending.refundTxHex?.let { put("refund_tx_hex", it) }
            pending.depositSats?.let { put("deposit_sats", it.toString()) }
            pending.fundingScriptType?.let { put("funding_script_type", it) }
            pending.sellerRefundAddress?.let { put("seller_refund_address", it) }
            // v23 (2026-09-02): carry the parties so the arbitrator — who has
            // NO local escrow row — can deliver the resolution to the buyer
            // AND seller. Pre-v23 the arbitrator resolved to nobody and funds
            // stayed locked in the multisig forever.
            local?.let {
                put("buyer_peer_id", it.buyerPeerId)
                put("seller_peer_id", it.sellerPeerId)
            }
        }
        val counterparty = when {
            local != null && local.buyerPeerId == pending.openedBy -> local.sellerPeerId
            local != null -> local.buyerPeerId
            else -> ""
        }
        var ok = true
        if (counterparty.isNotBlank()) {
            ok = rnsTransport.sendDispute(
                toPeerId = counterparty,
                escrowId = pending.escrowId,
                openedBy = pending.openedBy,
                reason = pending.reason,
                fields = fields
            ).isSuccess
        }
        // Arbitrator delivery over LXMF (blank = disabled). BEST-EFFORT:
        // an offline arbitrator must not block the party's dispute from
        // opening (disputeDeliveryVerdict) — the sweep's pending-dispute
        // retry reaches it later.
        if (NeoP2PConfig.ARBITRATOR_PEER_ID.isNotBlank()) {
            val arbOk = rnsTransport.sendDispute(
                toPeerId = NeoP2PConfig.ARBITRATOR_PEER_ID,
                escrowId = pending.escrowId,
                openedBy = pending.openedBy,
                reason = pending.reason,
                fields = fields
            ).isSuccess
            if (!arbOk) {
                Log.d(TAG, "Arbitrator not reached for dispute ${pending.escrowId} — best-effort, will retry on announce")
            }
        }
        val delivered = EscrowService.disputeDeliveryVerdict(ok)
        return if (delivered) Result.success(Unit) else Result.failure(Exception("LXMF dispute delivery failed"))
    }

    /**
     * Slice 3 (2026-09-01): durable retry for evidence/resolution deliveries
     * saved by [PendingArbitrationStore] when the initial LXMF send failed.
     * Re-sends to every remaining target; a row is dropped only when every
     * target acks. Receiving side is idempotent: evidence dedups by content,
     * and a resolution for an already-resolved dispute is skipped.
     */
    private suspend fun retryPendingArbitration() {
        try {
            val pendingEvidences = pendingArbitrationStore.allEvidence()
            for (p in pendingEvidences) {
                val remaining = p.targets.filter { target ->
                    val ok = rnsTransport.sendEvidence(
                        toPeerId = target,
                        escrowId = p.escrowId,
                        submitter = p.submitter,
                        description = p.description,
                        mimeType = p.mimeType,
                        imageBytes = runCatching {
                            android.util.Base64.decode(p.imageBase64, android.util.Base64.NO_WRAP)
                        }.getOrNull() ?: ByteArray(0)
                    ).isSuccess
                    if (!ok) Log.w(TAG, "Pending evidence ${p.escrowId} still failing to $target")
                    ok
                }
                if (remaining.isEmpty()) {
                    pendingArbitrationStore.removeEvidence(p.escrowId)
                    Log.i(TAG, "Retried pending evidence ${p.escrowId} delivered")
                } else if (remaining.size != p.targets.size) {
                    // Partial: keep only the undelivered targets for the next sweep.
                    pendingArbitrationStore.saveEvidence(p.copy(targets = remaining))
                }
            }
            val pendingResolutions = pendingArbitrationStore.allResolutions()
            for (p in pendingResolutions) {
                // Skip escrows the arbitrator already resolved (resolved=true).
                if (runCatching {
                        arbitratorDisputeDao.getById(p.escrowId)?.resolved == true
                    }.getOrDefault(false)
                ) {
                    pendingArbitrationStore.removeResolution(p.escrowId)
                    continue
                }
                val remaining = p.targets.filter { target ->
                    val ok = rnsTransport.sendResolution(
                        toPeerId = target,
                        escrowId = p.escrowId,
                        decision = p.decision,
                        arbitratorSigHex = p.arbitratorSigHex,
                        notes = p.notes,
                        sellerRefundAddress = p.sellerRefundAddress,
                        signedTxHex = p.signedTxHex
                    ).isSuccess
                    if (!ok) Log.w(TAG, "Pending resolution ${p.escrowId} still failing to $target")
                    ok
                }
                if (remaining.isEmpty()) {
                    pendingArbitrationStore.removeResolution(p.escrowId)
                    Log.i(TAG, "Retried pending resolution ${p.escrowId} delivered")
                } else if (remaining.size != p.targets.size) {
                    pendingArbitrationStore.saveResolution(p.copy(targets = remaining))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "retryPendingArbitration failed: ${e.message}")
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
                // Re-publish the healed dispute over LXMF so the arbitrator
                // sees both buttons.
                try {
                    val pending = com.neop2p.data.local.PendingDisputeStore.PendingDispute(
                        escrowId = d.escrow_id,
                        openedBy = d.opened_by,
                        reason = d.reason,
                        redeemScriptHex = d.redeem_script_hex,
                        psbtHex = psbt,
                        refundTxHex = d.refund_tx_hex,
                        depositSats = d.deposit_sats,
                        fundingScriptType = d.funding_script_type,
                        sellerRefundAddress = d.seller_refund_address
                    )
                    publishDisputeRns(pending, escrow)
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

    /** Re-attempt the transport start, bypassing the `running` short-circuit. */
    suspend fun retryTransport() {
        rnsTransport.start().onFailure {
            Log.w(TAG, "Transport retry failed: ${it.message}")
            lastTransportStartFailure = it
        }
        updateTransportReady()
    }

    suspend fun stop() {
        if (!running) return
        running = false
        inboundJob?.cancel()
        inboundJob = null
        notifyInboundJob?.cancel()
        notifyInboundJob = null
        escrowTransitionJob?.cancel()
        escrowTransitionJob = null
        escrowSweepJob?.cancel()
        escrowSweepJob = null
        offerReannounceJob?.cancel()
        offerReannounceJob = null
        rnsTransport.stop()
        updateTransportReady()
    }

    companion object {
        private const val TAG = "P2POrchestrator"
        private const val ESCROW_SWEEP_INTERVAL_MS = 60_000L

        /**
         * Dispute re-delivery gate (2026-09-02): a dispute event is processed
         * only when the arbitrator's feed row is NOT yet resolved. The LXMF
         * router retries DIRECT messages until a delivery receipt, and the
         * party's 60s sweep re-sends pending disputes — so the same dispute
         * arrives repeatedly. Once resolved, every re-delivery is stale and
         * must be dropped (no re-persist, no re-notify). Mirrored by
         * DisputeRedeliveryGateTest.
         */
        fun shouldProcessDispute(alreadyResolved: Boolean): Boolean = !alreadyResolved
    }
}
