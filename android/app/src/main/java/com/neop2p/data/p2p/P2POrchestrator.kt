package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.local.DeletedOfferStore
import com.neop2p.data.local.PeerBindingStore
import com.neop2p.data.local.dao.DisputeEvidenceDao
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.entity.DisputeEvidenceEntity
import com.neop2p.data.local.toDomain
import com.neop2p.data.p2p.EvidenceRetry
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
    private val escrowDao: com.neop2p.data.local.dao.EscrowDao,
    private val notificationDispatcher: NotificationDispatcher,
    private val appForegroundTracker: AppForegroundTracker,
    private val walletWatcher: WalletWatcher,
    private val disputeEvidenceDao: DisputeEvidenceDao,
    private val pendingDisputeStore: com.neop2p.data.local.PendingDisputeStore,
    private val pendingArbitrationStore: com.neop2p.data.local.PendingArbitrationStore,
    private val peerBindingStore: PeerBindingStore,
    private val scope: CoroutineScope
) {
    @Volatile private var running = false
    @Volatile private var inboundJob: Job? = null
    @Volatile private var notifyInboundJob: Job? = null
    @Volatile private var escrowTransitionJob: Job? = null
    @Volatile private var escrowSweepJob: Job? = null
    @Volatile private var offerReannounceJob: Job? = null
    @Volatile private var transportHealthJob: Job? = null

    private val _transportReady = MutableStateFlow(false)
    val transportReady: StateFlow<Boolean> = _transportReady.asStateFlow()

    @Volatile private var lastTransportStartFailure: Throwable? = null
    val transportStartFailure: Throwable? get() = lastTransportStartFailure

    // P7.4: bounded failover backoff for the 60s self-heal retry.
    private val transportFailover = NodeFailoverPolicy()
    private var transportFailoverState = NodeFailoverPolicy.State()

    private fun updateTransportReady() {
        _transportReady.value = rnsTransport.state.value.isRunning
    }

    /** H4 (2026-09-11): per-peer token bucket gating both inbound ingest paths. */
    private val inboundRateLimiter = PerPeerRateLimiter()

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

    /** Bounded deferral for pre-key bundles whose identity binding has not arrived. */
    private data class DeferredPreKeyBundle(
        val bundle: ByteArray,
        val senderDestHash: String,
        val firstSeenMs: Long,
    )
    private val deferredPreKeyBundles =
        java.util.concurrent.ConcurrentHashMap<String, DeferredPreKeyBundle>()

    /**
     * Option 1: a pre-key bundle may open a chat session only against a
     * verified RNS identity binding, pinned to the invite hash when present.
     */
    private suspend fun handlePreKeyBundle(
        peerId: String,
        bundleBytes: ByteArray,
        senderDestHash: String,
        authenticated: Boolean,
    ) {
        val verifiedIdentityHash = rnsTransport.verifiedDestFor(peerId)
        val verdict = ChatSessionBindingGate.verdict(
            verifiedForSender = rnsTransport.isVerifiedSender(peerId, senderDestHash),
            verifiedIdentityHash = verifiedIdentityHash,
            expectedInviteHash = peerBindingStore.expectedFor(peerId),
            storedSessionHash = signal.storedIdentityHash(peerId),
        )
        when (verdict) {
            ChatSessionBindingGate.Verdict.ALLOW ->
                establishPreKeySession(peerId, bundleBytes, authenticated, verifiedIdentityHash)
            ChatSessionBindingGate.Verdict.UNVERIFIED ->
                deferPreKeyBundle(peerId, bundleBytes, senderDestHash)
            ChatSessionBindingGate.Verdict.INVITE_MISMATCH -> {
                peerBindingStore.recordWarning(peerId, PeerBindingStore.WARNING_INVITE_MISMATCH)
                Log.w(TAG, "Refusing chat session with $peerId: verified identity does not match invite")
            }
            ChatSessionBindingGate.Verdict.IDENTITY_CHANGED -> {
                peerBindingStore.recordWarning(peerId, PeerBindingStore.WARNING_IDENTITY_CHANGED)
                Log.w(TAG, "Refusing chat session with $peerId: chat identity changed")
            }
        }
    }

    private suspend fun establishPreKeySession(
        peerId: String,
        bundleBytes: ByteArray,
        authenticated: Boolean,
        verifiedIdentityHash: String?,
    ) {
        val hadSession = signal.hasStoredSession(peerId)
        val bundle = try {
            signal.deserializeBundle(bundleBytes)
        } catch (e: com.neop2p.data.p2p.ratchet.PeerMustUpgradeException) {
            peerBindingStore.recordWarning(peerId, PeerBindingStore.WARNING_PEER_MUST_UPGRADE)
            Log.w(TAG, "Refusing chat with $peerId: peer must upgrade to E2EE v2")
            return
        }
        signal.createSession(
            peerId,
            bundle,
            authenticated = authenticated,
            verifiedIdentityHashHex = verifiedIdentityHash,
        ).onSuccess {
            peerBindingStore.clearWarning(peerId)
            deferredPreKeyBundles.remove(peerId)
            if (!hadSession) {
                signal.sendPreKeyBundle(peerId)
                    .onSuccess { reply ->
                        val replyEnv = EnvelopeCodec.encode(reply)
                        val ok = rnsTransport.send(peerId, replyEnv.data, replyEnv.type).isSuccess
                        if (!ok) queue.send(peerId, reply)
                    }
            }
            // E2EE v2: the initiator performs the first DH ratchet step and
            // sends the header-only third handshake shot so the responder can
            // derive its receiving chain and start sending.
            signal.consumePendingInitHeader()?.let { header ->
                val init = EnvelopeCodec.encode(AppMessage.RatchetInit(peerId, header))
                val ok = rnsTransport.send(peerId, init.data, init.type).isSuccess
                if (!ok) queue.send(peerId, AppMessage.RatchetInit(peerId, header))
            }
        }.onFailure { err ->
            Log.w(TAG, "Chat session with $peerId not established: ${err.message}")
        }
    }

    private fun deferPreKeyBundle(peerId: String, bundleBytes: ByteArray, senderDestHash: String) {
        if (deferredPreKeyBundles.size >= MAX_DEFERRED_PREKEYS &&
            !deferredPreKeyBundles.containsKey(peerId)
        ) {
            deferredPreKeyBundles.minByOrNull { it.value.firstSeenMs }?.key
                ?.let { deferredPreKeyBundles.remove(it) }
        }
        deferredPreKeyBundles[peerId] =
            DeferredPreKeyBundle(bundleBytes, senderDestHash, System.currentTimeMillis())
        Log.d(TAG, "Deferred chat pre-key bundle from $peerId (no verified binding yet)")
    }

    /** Retry held bundles for [onlyPeerId] (or all when null). Fail-closed. */
    private suspend fun flushDeferredPreKeyBundles(onlyPeerId: String? = null) {
        if (deferredPreKeyBundles.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((peerId, deferred) in deferredPreKeyBundles) {
            if (onlyPeerId != null && peerId != onlyPeerId) continue
            if (now - deferred.firstSeenMs > DEFERRED_PREKEY_TTL_MS) {
                deferredPreKeyBundles.remove(peerId)
                Log.d(TAG, "Dropping expired deferred pre-key bundle from $peerId")
                continue
            }
            val verifiedIdentityHash = rnsTransport.verifiedDestFor(peerId)
            if (rnsTransport.isVerifiedSender(peerId, deferred.senderDestHash) &&
                !verifiedIdentityHash.isNullOrBlank()
            ) {
                deferredPreKeyBundles.remove(peerId)
                establishPreKeySession(
                    peerId,
                    deferred.bundle,
                    authenticated = true,
                    verifiedIdentityHash = verifiedIdentityHash,
                )
            }
        }
    }

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
            // A fresh session starts with no in-flight transfer: fetch anything
            // the propagation node queued while we were offline.
            runCatching { rnsTransport.requestPropagationSync(force = true) }
            reputation.initialize()
            // Fix 2: scan for stale escrows on startup so a FUNDED-but-stalled
            // escrow auto-refunds (and an unfunded one auto-cancels). Idempotent.
            escrowService.initialize()
            offerRouter.startListening(scope)
            escrowRouter.startListening(scope)
            listenInbound()
            launchPeerDrain()
            notifyInboundChat()
            collectDeliveryUpdates()
            collectEscrowTransitions()
            collectChatKeyChanges()
            sweepStaleEscrows()
            monitorTransportHealth()
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

    /**
     * E2EE v2: surface a refused key change / legacy peer as a chat banner.
     * The crypto layer emits the peerId; the store carries it to the UI.
     */
    private fun collectChatKeyChanges() {
        scope.launch {
            signal.keyChanged.collect { peerId ->
                peerBindingStore.recordWarning(peerId, PeerBindingStore.WARNING_CHAT_KEY_CHANGED)
            }
        }
        scope.launch {
            signal.peerMustUpgrade.collect { peerId ->
                peerBindingStore.recordWarning(peerId, PeerBindingStore.WARNING_PEER_MUST_UPGRADE)
            }
        }
    }

    private fun listenInbound() {
        inboundJob?.cancel()
        inboundJob = scope.launch {
            rnsTransport.incomingMessages.collect { env ->
                val msg = EnvelopeCodec.decode(env) ?: return@collect
                // F4: key the limiter by the unclaimable sender destination,
                // not the self-asserted peerId (a hostile peer can rotate the
                // claimed peerId to evade a peerId-keyed bucket). Fall back to
                // the peerId only when the dest hash is absent.
                val limiterKey = env.senderDestHash.ifBlank { msg.from }
                if (!inboundRateLimiter.tryAcquire(limiterKey)) {
                    Log.w(TAG, "Dropping inbound ${msg.type} from $limiterKey: rate limit exceeded")
                    return@collect
                }
                when (msg) {
                    // msg.from is the peer requesting our bundle; reply to them.
                    // Direct send (the requester is online — it just sent the
                    // request), queue only as a fallback: a queued reply would
                    // sit until the requester's next announce (≤20s) and blow
                    // the requester's handshake wait.
                    is AppMessage.PreKeyRequest -> {
                        signal.sendPreKeyBundle(msg.from)
                            .onSuccess { bundle ->
                                val env = EnvelopeCodec.encode(bundle)
                                val ok = rnsTransport.send(msg.from, env.data, env.type).isSuccess
                                if (!ok) queue.send(msg.from, bundle)
                            }
                    }
                    is AppMessage.PreKeyBundle ->
                        handlePreKeyBundle(msg.from, msg.bundle, env.senderDestHash, env.authenticated)
                    is AppMessage.RatchetInit -> signal.handleRatchetInit(msg.from, msg.headerBytes)
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
                // F4: key the limiter by the unclaimable sender destination, not
                // the self-asserted peerId.
                val limiterKey = env.senderDestHash.ifBlank { env.fromPeerId }
                if (!inboundRateLimiter.tryAcquire(limiterKey)) {
                    Log.w(TAG, "Dropping inbound signaling ${env.type} from $limiterKey: rate limit exceeded")
                    return@collect
                }
                when (env.type) {
                    "offer_status" -> {
                        // F5 (2026-09-23): offer_status was the only signaling
                        // type with no sender verification and a body-supplied
                        // authorPeerId — any peer could cancel/pause/hijack an
                        // OPEN offer and plant buyer fields. Bind to the
                        // authenticated sender; never trust the body author.
                        val author = SignalingSenderGate.authorOf(
                            verifiedForSender = rnsTransport.isVerifiedSender(
                                env.fromPeerId, env.senderDestHash
                            ),
                            fromPeerId = env.fromPeerId
                        )
                        if (author == null) {
                            Log.w(
                                TAG,
                                "Dropping offer_status: sender ${env.fromPeerId} has no verified identity binding"
                            )
                            return@collect
                        }
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
                            buyerPubKeyHex = obj["buyer_pubkey_hex"]?.jsonPrimitive?.content,
                            buyerAddressAttestation = obj["buyer_address_attestation"]?.jsonPrimitive?.content,
                            authorPeerId = author
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
                        // 2026-09-24: an attestation is reputation-bearing and
                        // sender-authenticated downstream — require a verified
                        // identity binding before it can be ingested.
                        val author = SignalingSenderGate.authorOf(
                            verifiedForSender = rnsTransport.isVerifiedSender(
                                env.fromPeerId, env.senderDestHash
                            ),
                            fromPeerId = env.fromPeerId
                        )
                        if (author == null) {
                            Log.w(TAG, "Dropping attestation: sender ${env.fromPeerId} has no verified identity binding")
                            return@collect
                        }
                        val json = env.data.toString(Charsets.UTF_8)
                        reputation.processAttestation(json, author)
                    }
                    "escrow_status" -> {
                        if (!rnsTransport.isVerifiedSender(env.fromPeerId, env.senderDestHash)) {
                            Log.w(TAG, "Dropping escrow_status: sender ${env.fromPeerId} has no verified identity binding (peer must upgrade)")
                            return@collect
                        }
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                env.data.toString(Charsets.UTF_8)
                            ).jsonObject
                        }.onFailure {
                            // Defense-in-depth (2026-09-14): a malformed
                            // escrow_status used to be dropped silently, which
                            // is how a corrupted RELEASED payload left the
                            // buyer stuck on CONFIRMING with no trace.
                            Log.w(TAG, "Dropping malformed escrow_status from ${env.fromPeerId}: ${it.message}")
                        }.getOrNull() ?: return@collect
                        escrowRouter.ingestEscrowStatus(obj, env.fromPeerId)
                        // C1d: if the local identity is the BUYER and the
                        // escrow just reached CONFIRMING, sign the payout with
                        // the buyer key and deliver the signature to the seller
                        // so their release can combine it (2-of-3). The buyer's
                        // mirrored row carries psbt_unsigned only when the
                        // seller published it; if absent, skip (the seller
                        // self-generates and the buyer signs on the next
                        // CONFIRMING re-publish).
                        val escrowId = obj["escrow_id"]?.jsonPrimitive?.content
                        val remoteStatus = obj["status"]?.jsonPrimitive?.content
                        if (escrowId != null && remoteStatus == "CONFIRMING") {
                            escrowService.signPayoutAsBuyerIfLocal(escrowId)
                        }
                        // C1d (2026-09-11): the seller side of the round-trip.
                        // The buyer echoes its payout signature back to the
                        // seller over escrow_status; the seller must VERIFY it
                        // against buyer_pubkey_hex and persist (storeBuyerSignature)
                        // before releaseWhenReady can broadcast the 2-of-3.
                        // storeBuyerSignature has zero other callers and does
                        // its own sender-independent crypto check, so any peer
                        // can drop a signature here — a forged one never
                        // persists. The sweep's releaseAwaitingBuyerSignatures
                        // then completes the release.
                        val remoteSig = obj["buyer_signature"]?.jsonPrimitive?.content
                        if (escrowId != null && !remoteSig.isNullOrBlank()) {
                            escrowService.storeBuyerSignature(escrowId, remoteSig)
                                .onFailure { Log.w(TAG, "C1d buyer signature rejected: ${it.message}") }
                        }
                    }
                    "dispute" -> {
                        if (!rnsTransport.isVerifiedSender(env.fromPeerId, env.senderDestHash)) {
                            Log.w(TAG, "Dropping dispute: sender ${env.fromPeerId} has no verified identity binding (peer must upgrade)")
                            return@collect
                        }
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                env.data.toString(Charsets.UTF_8)
                            ).jsonObject
                        }.getOrNull() ?: return@collect
                        applyDisputeEvent(obj, env.fromPeerId)
                    }
                    "evidence" -> {
                        if (!rnsTransport.isVerifiedSender(env.fromPeerId, env.senderDestHash)) {
                            Log.w(TAG, "Dropping evidence: sender ${env.fromPeerId} has no verified identity binding (peer must upgrade)")
                            return@collect
                        }
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                env.data.toString(Charsets.UTF_8)
                            ).jsonObject
                        }.getOrNull() ?: return@collect
                        applyEvidenceEvent(obj, env.fromPeerId)
                    }
                    "resolution" -> {
                        if (!rnsTransport.isVerifiedSender(env.fromPeerId, env.senderDestHash)) {
                            Log.w(TAG, "Dropping resolution: sender ${env.fromPeerId} has no verified identity binding (peer must upgrade)")
                            return@collect
                        }
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
                        val sourcePeerId = env.fromPeerId
                        val offerId = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(offerJson)
                                .jsonObject["offer_id"]?.jsonPrimitive?.content
                        }.getOrNull()
                        // G1/F5 (2026-09-23): only ingest an offer that answers
                        // a digest WE requested from THIS peer. Previously a null
                        // digest fell through and an unsolicited offer was
                        // ingested, enabling feed poisoning and creator
                        // impersonation.
                        val digestPeerId = offerId?.let { pendingOfferPeers.remove(it) }
                        val digest = offerId?.let { pendingOfferDigests.remove(it) }
                        if (!OfferIngestGate.shouldIngest(
                                offerId = offerId,
                                digestPresent = digest != null,
                                digestPeerId = digestPeerId,
                                sourcePeerId = sourcePeerId
                            ) || digest == null
                        ) {
                            Log.w(TAG, "Dropping unsolicited/mismatched offer from $sourcePeerId (id=$offerId)")
                            return@collect
                        }
                        if (!RnsOfferDigest.verify(offerJson, digest)) {
                            Log.w(TAG, "Offer $offerId failed digest commitment — dropping")
                            return@collect
                        }
                        offerRouter.ingestRnsOffer(offerJson, sourcePeerId)
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
                        // 2026-09-06: an OBSERVER (neither creator nor matched
                        // peer) has no business keeping a finished trade's
                        // offer — delete the row so it disappears from the
                        // feed entirely instead of lingering as a locked row.
                        // Party rows stay (marked COMPLETED below): the
                        // buyer's escrow detail reads fiat + bank details
                        // from the offer row, and the creator's row is their
                        // own history. The tombstone store prevents a stale
                        // re-announce from resurrecting the deleted row.
                        val myPeerId = runCatching { identityManager.myPeerId() }
                            .getOrDefault("")
                        if (OfferFeedGate.tombstoneDeletesRow(
                                localStatus = existing.status,
                                creatorPeerId = existing.creator_peer_id,
                                matchedPeerId = existing.matched_peer_id,
                                myPeerId = myPeerId
                            )
                        ) {
                            offerDao.delete(existing)
                            deletedOfferStore.markDeleted(offerId, existing.nostr_event_id)
                            Log.i(TAG, "Deleted observer row for terminal offer $offerId (was ${existing.status})")
                            return@collect
                        }
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
            // Chat rows carry a ciphertext-derived token so the deferred
            // delivery (queued while the peer was offline) still updates the
            // persisted row instead of leaving it "pending" forever.
            val token = (msg as? AppMessage.Chat)?.let { ChatDeliveryToken.of(it.ciphertext) }
            if (token != null) {
                rnsTransport.sendTracked(peerId, env.data, env.type, token).isSuccess
            } else {
                rnsTransport.send(peerId, env.data, env.type).isSuccess
            }
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
                flushDeferredPreKeyBundles(peerId)
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

    /** Persist LXMF delivery-status changes onto their chat rows. */
    private fun collectDeliveryUpdates() {
        scope.launch {
            rnsTransport.deliveryUpdates.collect { update ->
                runCatching { chatRouter.applyDeliveryStatus(update.token, update.status) }
                    .onFailure { Log.w(TAG, "delivery status apply failed: ${it.message}") }
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
                        context.getString(R.string.notif_escrow_disputed_title) to
                            context.getString(R.string.notif_escrow_disputed_body)
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
        // FUNDING is not disputable (2026-09-05): the deposit is either not
        // yet broadcast (nothing to arbitrate — the 30-min funding window
        // auto-cancels) or in flight (unconfirmed — the arbitrator's
        // payout/refund would spend a nonexistent output). Mirrors
        // EscrowService.canDisputeFromStatus.
        if (s == EscrowStatus.FUNDING) return false
        return true
    }

    /**
     * Apply a dispute event (LXMF "dispute") received over RNS. Phase 3: the
     * app is only ever a PARTY, so this flips the LOCAL escrow to DISPUTED
     * (idempotent) and notifies. The authenticated sender must be a party of
     * the local escrow — the wire's peer ids are attacker-controlled and are
     * never the gate. A dispute for an escrow we do not hold is dropped
     * (there is no arbitrator view in this build).
     */
    private suspend fun applyDisputeEvent(obj: kotlinx.serialization.json.JsonObject, fromPeerId: String) {
        val inbound = ArbitrationIngest.parseDispute(obj) ?: return
        try {
            // NOTE: getEscrow has a resume-heal side effect (re-publishes
            // escrow_status) — keep the call before the gates.
            val local = escrowService.getEscrow(inbound.escrowId) ?: return
            val myPeerId = runCatching { identityManager.myPeerId() }.getOrDefault("")
            if (fromPeerId.isBlank() || fromPeerId == myPeerId ||
                (fromPeerId != local.buyerPeerId && fromPeerId != local.sellerPeerId)
            ) {
                Log.w(TAG, "Dropping dispute ${inbound.escrowId}: sender $fromPeerId is not a party of this escrow")
                return
            }
            if (!isDisputableStatus(local)) return
            escrowService.disputeEscrow(inbound.escrowId)
            notificationDispatcher.notifyEscrow(
                inbound.escrowId, "disputed",
                context.getString(R.string.notif_dispute_opened_title),
                (obj["reason"]?.jsonPrimitive?.content)?.let {
                    context.getString(R.string.notif_dispute_opened_body, it)
                } ?: context.getString(R.string.notif_dispute_opened_fallback)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply dispute event: ${e.message}")
        }
    }

    /**
     * Apply a dispute-evidence event (LXMF "evidence") received over RNS:
     * persist so both parties can see it in DisputeEvidenceScreen and notify.
     */
    private suspend fun applyEvidenceEvent(obj: kotlinx.serialization.json.JsonObject, fromPeerId: String) {
        val inbound = ArbitrationIngest.parseEvidence(obj) ?: return
        // Party side (Phase 3): evidence is kept so both sides can see it in
        // DisputeEvidenceScreen. Submitter-auth, known-escrow, and the size cap
        // stay in :core; there is no arbitrator row to consult any more.
        val decision = ArbitrationIngest.decideEvidence(
            inbound = inbound,
            fromPeerId = fromPeerId,
            hasDisputeRow = { false },
            hasLocalEscrow = { runCatching { escrowService.getEscrow(inbound.escrowId) != null }.getOrDefault(false) },
        )
        when (decision) {
            is EvidenceIngestDecision.Drop -> { Log.w(TAG, decision.reason); return }
            is EvidenceIngestDecision.Accept -> {
                val bytes = decision.imageData
                if (bytes == null || bytes.isEmpty()) return
                try {
                    val existing = disputeEvidenceDao.getEvidenceForEscrow(inbound.escrowId)
                    val isDuplicate = existing.any {
                        it.submitter_peer_id == decision.submitter && it.description == decision.description &&
                            it.image_data.size == bytes.size && it.image_data.contentEquals(bytes)
                    }
                    if (!isDuplicate) {
                        disputeEvidenceDao.insert(
                            DisputeEvidenceEntity(
                                evidence_id = java.util.UUID.randomUUID().toString(),
                                escrow_id = inbound.escrowId,
                                submitter_peer_id = decision.submitter,
                                description = decision.description,
                                mime_type = decision.mimeType,
                                image_data = bytes,
                                submitted_at = System.currentTimeMillis()
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist evidence ${inbound.escrowId}: ${e.message}")
                }
            }
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
        // Verify the arbitrator's signature BEFORE applying the resolution
        // (2026-09-02): a garbage sig must not move funds. Phase 3: the app
        // is a party, so the redeem script / deposit / tx come from its own
        // escrow row (there is no arbitrator dispute row).
        val sigValid = runCatching {
            val escrow = escrowService.getEscrow(escrowId)
            val redeemHex = escrow?.redeemScriptHex
            if (redeemHex.isNullOrBlank()) {
                Log.w(TAG, "Resolution $escrowId: no redeem script to verify against")
                false
            } else {
                escrowService.verifyArbitratorSignature(
                    txHex = obj["signed_tx_hex"]?.jsonPrimitive?.content
                        ?: escrow.psbtUnsigned?.toString(Charsets.UTF_8),
                    redeemScriptHex = redeemHex,
                    arbitratorSigHex = sigHex,
                    depositSats = escrow.fundedAmountSats ?: escrow.depositAmountSats,
                    fundingScriptType = escrow.fundingScriptType.name
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
        // The escrow's terminal status IS the resolved record (Phase 3).
        val wasResolved = runCatching {
            val s = escrowService.getEscrow(escrowId)?.status
            s == EscrowStatus.RELEASED || s == EscrowStatus.REFUNDED || s == EscrowStatus.CANCELLED
        }.getOrDefault(false)
        try {
            // Persist the seller's refund address BEFORE applying the
            // decision: storeArbitrationDecision builds the refund tx
            // from escrow.refund_destination, and the address travels
            // in the resolution event. F2: a non-blank local attested
            // destination that contradicts the incoming one must block
            // the whole resolution — never overwrite and never apply.
            val refundAddr = obj["seller_refund_address"]?.jsonPrimitive?.content
            if (!refundAddr.isNullOrBlank() &&
                !escrowService.confirmRefundDestination(escrowId, refundAddr)
            ) {
                Log.w(TAG, "Resolution $escrowId: refund destination mismatch — funds NOT moved")
                notificationDispatcher.notifyEscrow(
                    escrowId, "resolution_blocked",
                    context.getString(R.string.notif_resolved_title),
                    "Resolution blocked: refund destination mismatch — funds NOT moved"
                )
                return
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
     * and funded-refund window (2 h) are enforced from a single scan at
     * startup otherwise, so a long-lived process would never auto-cancel or
     * auto-refund a stalled escrow. Sweeping every 60s keeps the deadlines
     * honest while the foreground service is up (idempotent: terminal
     * statuses are skipped, so re-scans are cheap no-ops).
     */
    private fun sweepStaleEscrows() {
        escrowSweepJob?.cancel()
        escrowSweepJob = scope.launch {
            while (isActive) {
                // Idle battery cadence (2026-09-07): backgrounded = 5-min
                // sweep. Timeout math is hour-scale (30min funding / 2h
                // refund / 24h payment), so a 5-min delay is invisible to
                // every deadline; pending-dispute/evidence retries are at
                // most 5 min slower. Foreground flips back to 60s.
                // F4: drop idle inbound rate-limiter buckets so the maxPeers
                // cap is enforced on the long-lived map (evictIdle is a no-op
                // while under the cap).
                inboundRateLimiter.evictIdle()
                val idle = !appForegroundTracker.isForeground.value
                rnsTransport.setIdleMode(idle)
                // Transport self-heal: if the RNS transport failed to start
                // (e.g. identity locked behind device auth at app launch),
                // retry every sweep — the user may have unlocked the phone
                // since. Idempotent: start() is a no-op once the session is up.
                if (!rnsTransport.state.value.isRunning) {
                    val healNow = System.currentTimeMillis()
                    if (transportFailover.canAttempt(transportFailoverState, healNow)) {
                        rnsTransport.start()
                            .onSuccess { transportFailoverState = transportFailover.onSuccess(transportFailoverState) }
                            .onFailure {
                                transportFailoverState = transportFailover.onFailure(transportFailoverState, healNow)
                                Log.w(TAG, "Transport retry failed: ${it.message}")
                                lastTransportStartFailure = it
                            }
                    }
                } else if (transportFailoverState != NodeFailoverPolicy.State()) {
                    transportFailoverState = transportFailover.onSuccess(transportFailoverState)
                }
                updateTransportReady()
                escrowService.expireStaleEscrows()
                sweepExpiredOffers()
                sweepStaleMatchedOffers()
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
                // C1d: release any local CONFIRMING escrow whose buyer payout
                // signature arrived while the seller's app was closed (the
                // storeBuyerSignature-triggered release missed). Idempotent:
                // releaseFunds is guarded by status + payout_tx_id, so once
                // broadcast this is a no-op.
                releaseAwaitingBuyerSignatures()
                flushDeferredPreKeyBundles()
                delay(if (idle) SWEEP_IDLE_INTERVAL_MS else ESCROW_SWEEP_INTERVAL_MS)
            }
        }
    }

    /**
     * Auto-delete expired offers. A creator-picked TTL (6h/12h/24h/48h) is a
     * commitment window — once `expires_at` passes, the offer is no longer
     * claimable and should leave the feed entirely, not linger as a greyed-out
     * row. Only OPEN/PAUSED offers are auto-deleted here: a locked offer
     * (MATCHED/ESCROWED) is a live match with funds in flight and must keep
     * its row so the escrow lifecycle (not a TTL) governs it, and terminal
     * offers already re-announce tombstones. Mirrors the manual delete path:
     * drop the Room row, stop re-announcing it, and tombstone it so a stale
     * re-announce can't resurrect it.
     */
    private suspend fun sweepExpiredOffers() {
        try {
            val expired = offerDao.getExpiredOpenOffers(System.currentTimeMillis())
            if (expired.isEmpty()) return
            for (offer in expired) {
                val domain = offer.toDomain()
                offerDao.delete(offer)
                rnsTransport.untrackOfferDigest(offer.offer_id)
                deletedOfferStore.markDeleted(offer.offer_id, offer.nostr_event_id)
                Log.i(TAG, "Auto-deleted expired offer ${offer.offer_id}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sweep expired offers: ${e.message}")
        }
    }

    /**
     * Auto-cancel MATCHED offers whose escrow was never created. The creator
     * picks the TTL for the OPEN window; the MATCHED→ESCROWED step is time-
     * limited separately: a buyer who accepted but whose seller never creates
     * the escrow must not hold the offer locked forever. After
     * [NeoP2PConfig.MATCHED_ESCROW_TIMEOUT_MS] (1h) from [locked_at], the
     * creator's device cancels the offer, clears the match, and syncs the
     * terminal status to the (former) matched peer via LXMF offer_status —
     * the same path as a manual decline/unlock, so the buyer's gate converges
     * and the lock is released.
     *
     * Scope guards (mirroring the escrow lifecycle's role gate):
     *  - Creator-only: the matched peer's mirrored row has a different
     *    `locked_at`/created_at and must not cancel the seller's offer.
     *  - No escrow row for the offer = still in the MATCHED window. Once an
     *    escrow exists the offer is ESCROWED and the escrow lifecycle owns it
     *    (funding timeout / refund / dispute — never this sweep).
     *  - Disputes live only on ESCROWED offers (they need an escrow row), so
     *    a disputed trade can never be auto-cancelled here.
     */
    private suspend fun sweepStaleMatchedOffers() {
        try {
            val myPeerId = identityManager.myPeerId()
            if (myPeerId.isBlank()) return
            val deadline = System.currentTimeMillis() - NeoP2PConfig.MATCHED_ESCROW_TIMEOUT_MS
            val stale = offerDao.getStaleMatchedOffers(deadline)
            if (stale.isEmpty()) return
            for (entity in stale) {
                if (entity.creator_peer_id != myPeerId) continue
                val hasEscrow = escrowDao.getEscrowByOfferId(entity.offer_id) != null
                if (hasEscrow) continue
                offerDao.updateStatusWithMatchedPeer(
                    entity.offer_id,
                    OfferStatus.CANCELLED.name,
                    ""
                )
                // Best-effort sync to the (former) matched peer. Blank/unknown
                // peers fail silently (sendSignaling throws on no path).
                if (!entity.matched_peer_id.isNullOrBlank()) {
                    runCatching {
                        rnsTransport.sendOfferStatus(
                            toPeerId = entity.matched_peer_id.orEmpty(),
                            offerId = entity.offer_id,
                            status = OfferStatus.CANCELLED.name,
                            matchedPeerId = entity.matched_peer_id.orEmpty(),
                            authorPeerId = myPeerId
                        )
                    }.onFailure { Log.w(TAG, "Stale-match CANCELLED sync failed: ${it.message}") }
                }
                Log.i(TAG, "Auto-cancelled stale MATCHED offer ${entity.offer_id} (escrow never created)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sweep stale MATCHED offers: ${e.message}")
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
            if (ids.isEmpty()) return
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
                    // Retain the FAILED targets (EvidenceRetry owns the
                    // polarity so the dispute and evidence loops cannot drift
                    // apart again). An EMPTY result means every target acked.
                    val undelivered = EvidenceRetry.undeliveredTargets(pending.targets) { target ->
                        val ok = publishDisputeToTarget(pending, local, target).isSuccess
                        if (!ok) Log.w(TAG, "Pending dispute $escrowId still failing to $target")
                        ok
                    }
                    if (undelivered.isEmpty()) {
                        pendingDisputeStore.remove(escrowId)
                        Log.i(TAG, "Retried pending dispute $escrowId delivered to all targets")
                    } else if (undelivered.size != pending.targets.size) {
                        pendingDisputeStore.save(pending.copy(targets = undelivered))
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
            // Task 2 (Phase 1): persisted outpoint first, live escrow fallback
            // (legacy pending rows have neither).
            (pending.fundingTxid ?: local?.fundingTxId)?.let { put("funding_txid", it) }
            (pending.fundingVout ?: local?.fundingVout)?.let { put("funding_vout", it.toString()) }
            // C9 (Phase 1): the redeem-script template + V1 maturity so the
            // arbitrator gates and resolves the right script shape.
            local?.scriptTemplate?.let { put("script_template", it.id) }
            local?.cltvLocktime?.let { put("cltv_locktime", it.toString()) }
            pending.sellerRefundAddress?.let { put("seller_refund_address", it) }
            // F2 (2026-09-12): role keys + role-signed destination attestations
            // (persisted value first, live escrow fallback for legacy rows).
            (pending.offerId ?: local?.offerId)?.let { put("offer_id", it) }
            (pending.buyerBtcAddress ?: local?.buyerBtcAddress)?.takeIf { it.isNotBlank() }
                ?.let { put("buyer_btc_address", it) }
            (pending.buyerPubKeyHex ?: local?.buyerPubKeyHex)?.let { put("buyer_pubkey_hex", it) }
            (pending.sellerPubKeyHex ?: local?.sellerPubKeyHex)?.let { put("seller_pubkey_hex", it) }
            (pending.tradeSats ?: local?.tradeAmountSats)?.let { put("trade_sats", it.toString()) }
            (pending.sellerRefundAttestation ?: local?.sellerRefundAttestation)
                ?.let { put("seller_refund_attestation", it) }
            (pending.buyerAddressAttestation ?: local?.buyerAddressAttestation)
                ?.let { put("buyer_address_attestation", it) }
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
            // Task 2 (Phase 1): persisted outpoint first, live escrow fallback
            // (legacy pending rows have neither).
            (pending.fundingTxid ?: local?.fundingTxId)?.let { put("funding_txid", it) }
            (pending.fundingVout ?: local?.fundingVout)?.let { put("funding_vout", it.toString()) }
            // C9 (Phase 1): the redeem-script template + V1 maturity so the
            // arbitrator gates and resolves the right script shape.
            local?.scriptTemplate?.let { put("script_template", it.id) }
            local?.cltvLocktime?.let { put("cltv_locktime", it.toString()) }
            pending.sellerRefundAddress?.let { put("seller_refund_address", it) }
            // F2 (2026-09-12): role keys + role-signed destination attestations.
            // Prefer the persisted pending value (the payload as opened), but
            // fall back to the LIVE escrow so a legacy pending row (saved by a
            // pre-F2 build) still enriches on retry.
            (pending.offerId ?: local?.offerId)?.let { put("offer_id", it) }
            (pending.buyerBtcAddress ?: local?.buyerBtcAddress)?.takeIf { it.isNotBlank() }
                ?.let { put("buyer_btc_address", it) }
            (pending.buyerPubKeyHex ?: local?.buyerPubKeyHex)?.let { put("buyer_pubkey_hex", it) }
            (pending.sellerPubKeyHex ?: local?.sellerPubKeyHex)?.let { put("seller_pubkey_hex", it) }
            (pending.tradeSats ?: local?.tradeAmountSats)?.let { put("trade_sats", it.toString()) }
            (pending.sellerRefundAttestation ?: local?.sellerRefundAttestation)
                ?.let { put("seller_refund_attestation", it) }
            (pending.buyerAddressAttestation ?: local?.buyerAddressAttestation)
                ?.let { put("buyer_address_attestation", it) }
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
     * Durable retry for evidence deliveries saved by [PendingArbitrationStore]
     * when the initial LXMF send failed (a kill before send, or a long-offline
     * arbitrator). A row is dropped only when every target acks; the receiving
     * side dedups by content.
     */
    private suspend fun retryPendingArbitration() {
        try {
            val pendingEvidences = pendingArbitrationStore.allEvidence()
            for (p in pendingEvidences) {
                // Retain the FAILED targets (EvidenceRetry owns the polarity so
                // an all-fail sweep can never delete the row and lose the data).
                val remaining = EvidenceRetry.undeliveredTargets(p.targets) { target ->
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
                } else {
                    // Keep only the undelivered targets (idempotent when nothing
                    // was delivered, so the row survives for the next sweep).
                    pendingArbitrationStore.saveEvidence(p.copy(targets = remaining))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "retryPendingArbitration failed: ${e.message}")
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

    /**
     * C1d (2026-09-11): broadcast the payout for every local CONFIRMING
     * escrow whose buyer payout signature is present but which has not yet
     * released (payout_tx_id is null). Heals the case where the buyer's
     * signature arrived while the seller's app was closed (the
     * storeBuyerSignature-triggered release missed). Idempotent: releaseFunds
     * is guarded by status + payout_tx_id, so once broadcast this is a no-op.
     */
    private suspend fun releaseAwaitingBuyerSignatures() {
        try {
            for (entity in escrowDao.getAllEscrowsSync()) {
                if (entity.status != EscrowStatus.CONFIRMING.name) continue
                if (entity.buyer_signature == null) continue
                if (entity.payout_tx_id != null) continue
                escrowService.releaseWhenReady(entity.escrow_id)
                    .onFailure { Log.w(TAG, "C1d sweep release for ${entity.escrow_id} failed: ${it.message}") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "C1d release sweep failed: ${e.message}")
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

    private fun monitorTransportHealth() {
        transportHealthJob?.cancel()
        transportHealthJob = scope.launch {
            var readySinceMs = 0L
            var lastRecoveryMs = 0L
            while (isActive) {
                val health = rnsTransport.interfaceHealth()
                val running = rnsTransport.state.value.isRunning
                if (running && readySinceMs == 0L) readySinceMs = System.currentTimeMillis()
                if (!running) readySinceMs = 0L
                val now = System.currentTimeMillis()
                if (health != null && TransportRecoveryPolicy.shouldRecover(
                        running = running,
                        onlineInterfaces = health.onlineTcp,
                        expectedInterfaces = health.totalTcp,
                        readySinceMs = readySinceMs,
                        nowMs = now,
                        lastRecoveryMs = lastRecoveryMs,
                    )
                ) {
                    Log.w(TAG, "Transport RUNNING with 0/${health.totalTcp} interfaces online — restarting")
                    lastRecoveryMs = now
                    runCatching { rnsTransport.restart() }
                    readySinceMs = 0L
                }
                delay(TransportRecoveryPolicy.CHECK_INTERVAL_MS)
            }
        }
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
        transportHealthJob?.cancel()
        transportHealthJob = null
        rnsTransport.stop()
        updateTransportReady()
    }

    companion object {
        private const val TAG = "P2POrchestrator"
        private const val ESCROW_SWEEP_INTERVAL_MS = 60_000L
        /** Idle (backgrounded) escrow sweep: 5 min. All timeouts this sweep
         *  enforces are hour-scale; a stalled-FUNDED escrow refunds ≤5 min
         *  later than at 60s cadence. Battery: 1,440 wakeups/day → 288. */
        private const val SWEEP_IDLE_INTERVAL_MS = 300_000L

        /** Bounded pre-key deferral (Option 1): ≤ N peers, 10-minute TTL. */
        private const val MAX_DEFERRED_PREKEYS = 16
        private const val DEFERRED_PREKEY_TTL_MS = 10 * 60 * 1000L
    }
}
