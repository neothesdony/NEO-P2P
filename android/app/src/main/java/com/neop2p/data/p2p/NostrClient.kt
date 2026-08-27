package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.NeoP2PConfig
import com.neop2p.data.local.dao.AttestationDao
import com.neop2p.data.local.dao.PeerDao
import com.neop2p.data.local.entity.AttestationEntity
import com.neop2p.data.p2p.store.PeerRegistry
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nostr client for peer discovery and trade offer broadcast.
 *
 * Uses NIP-01 for event publishing/subscription with proper signing.
 * Kind conventions:
 * - kind: 33333 — Trade offer
 * - kind: 33334 — Trade response/interest
 * - kind: 33335 — Peer attestation (reputation)
 * - kind: 33336 — Offer status/lock update
 * - kind: 33386 — Dispute opened (arbitration)
 * - kind: 33387 — Dispute evidence (receipt image)
 * - kind: 33388 — Arbitration resolution (arbitrator-signed payout/refund)
 * - kind: 10065 — NIP-65 relay list metadata
 */
@Singleton
class NostrClient @Inject constructor(
    private val identityManager: IdentityManager,
    private val peerRegistry: PeerRegistry,
    private val peerDao: PeerDao,
    private val attestationDao: AttestationDao
) {
    companion object {
        private const val TAG = "NostrClient"
        private const val KIND_TRADE_OFFER = 33333
        private const val KIND_TRADE_RESPONSE = 33334
        private const val KIND_ATTESTATION = 33335
        private const val KIND_RELAY_META = 10065
        private const val KIND_OFFER_DELETE = 5  // NIP-09 deletion event
        private const val KIND_OFFER_STATUS = 33336  // custom: offer status/lock update
        private const val KIND_ESCROW_STATUS = 33337  // custom: escrow lifecycle sync (2-party)
        private const val KIND_DISPUTE = 33386      // custom: dispute opened
        private const val KIND_EVIDENCE = 33387     // custom: dispute evidence
        private const val KIND_RESOLUTION = 33388   // custom: arbitration resolution

        // Reconnection constants
        private const val RECONNECT_BASE_DELAY_MS = 1_000L     // 1 second initial
        private const val RECONNECT_MAX_DELAY_MS = 60_000L    // 1 minute cap
        private const val RECONNECT_JITTER_MS = 500L          // ±500ms jitter
        private const val RELAY_CONNECT_TIMEOUT_MS = 10_000L  // 10s per attempt
    }

    data class NostrRelay(
        val url: String,
        val isConnected: Boolean = false,
        val latencyMs: Long = 0
    )

    private val _relays = MutableStateFlow(
        NeoP2PConfig.DEFAULT_NOSTR_RELAYS.map { NostrRelay(it) }
    )
    val relays: StateFlow<List<NostrRelay>> = _relays.asStateFlow()

    /** Our own Nostr pubkey (set on connect); used to ignore our own events. */
    private var ownPubkey: String = ""

    private val _offers = MutableSharedFlow<JsonObject>(replay = 100)
    val offers: SharedFlow<JsonObject> = _offers.asSharedFlow()

    // NIP-09 deletion events (offer ids that other peers have removed).
    private val _deletions = MutableSharedFlow<String>(replay = 100)
    val deletions: SharedFlow<String> = _deletions.asSharedFlow()

    // NIP-09 deletion events signed by OUR OWN pubkey. The relay replays these
    // on every connect — they backfill the tombstone store so offers deleted
    // before this fix still don't resurrect.
    private val _ownDeletions = MutableSharedFlow<String>(replay = 100)
    val ownDeletions: SharedFlow<String> = _ownDeletions.asSharedFlow()

    // Offer status/lock updates (offerId -> new status). Published when a peer
    // accepts an offer so other devices mark it locked (MATCHED). Also carries
    // the accepting peer's id so the offer creator knows WHO matched, the
    // buyer's BTC payout address (U1) so the seller can build the payout, and
    // the authoring peer's id (U4) so decline events can be authorized.
    private val _offerStatusUpdates = MutableSharedFlow<OfferStatusUpdate>(replay = 100)
    val offerStatusUpdates: SharedFlow<OfferStatusUpdate> = _offerStatusUpdates.asSharedFlow()

    /** Structured offer status event (kind:33336) with matched peer + buyer address. */
    data class OfferStatusUpdate(
        val offerId: String,
        val status: String,
        val matchedPeerId: String?,
        val buyerBtcAddress: String?,
        val authorPeerId: String?
    )

    // Escrow lifecycle events (kind:33337) received from the relay. Content:
    // {escrow_id, status, ts, ...mutable escrow fields}. Consumers
    // (EscrowRouter) converge remote devices on the same escrow row so the
    // happy path works two-party, not just single-key.
    private val _escrowStatusEvents = MutableSharedFlow<JsonObject>(replay = 100)
    val escrowStatusEvents: SharedFlow<JsonObject> = _escrowStatusEvents.asSharedFlow()

    // Verified attestations (kind:33335) received from the relay, emitted after
    // persistence. Consumers (reputation, profile) use this instead of the raw
    // event so they only ever see signature-verified data.
    private val _attestations = MutableSharedFlow<AttestationEntity>(replay = 100)
    val attestations: SharedFlow<AttestationEntity> = _attestations.asSharedFlow()

    // Dispute events (kind:33386) received from the relay. Content JSON:
    // {escrow_id, opened_by, reason, opened_at}. Consumers (arbitrator feed,
    // parties) sync local escrow status to DISPUTED.
    private val _disputes = MutableSharedFlow<JsonObject>(replay = 100)
    val disputes: SharedFlow<JsonObject> = _disputes.asSharedFlow()

    // Dispute evidence events (kind:33387) received from the relay. Content:
    // {escrow_id, submitter, description, mime_type, image_base64}.
    private val _evidence = MutableSharedFlow<JsonObject>(replay = 100)
    val evidence: SharedFlow<JsonObject> = _evidence.asSharedFlow()

    // Arbitration resolutions (kind:33388) received from the relay. Content:
    // {escrow_id, decision, arbitrator_sig_hex, notes, decided_at}. Parties
    // apply the status locally so the payout/refund can be broadcast with the
    // arbitrator's signature.
    private val _resolutions = MutableSharedFlow<JsonObject>(replay = 100)
    val resolutions: SharedFlow<JsonObject> = _resolutions.asSharedFlow()

    private var httpClient: HttpClient? = null
    private var activeSockets = mutableListOf<WebSocketSession>()
    private var scope: CoroutineScope? = null

    // Maps a relay URL to its currently-open persistent WebSocket so publishes
    // reuse the long-lived connection (rather than opening an ephemeral one that
    // closes before the relay can persist the event).
    private val socketsByRelay = ConcurrentHashMap<String, WebSocketSession>()

    // Tracks NIP-20 (EVENT/OK) acknowledgements for publishes awaiting relay
    // confirmation, keyed by "eventId|relayUrl". A deferred completes when the
    // relay replies OK:true for that event. This is what lets a publish know the
    // offer was actually STORED (not just transmitted) on that relay.
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * True if [url] is one of the NEO-P2P self-hosted relays (custom-minipc.com).
     * Trade offers are only subscribed on these relays — the public fallback
     * relays (nos.lol, relay.damus.io) carry arbitrary kind-33333 events from
     * unrelated Nostr users that are NOT NEO-P2P offers.
     */
    private fun isCustomRelay(url: String): Boolean =
        url.contains("custom-minipc.com")

    /**
     * Connect to Nostr relays and start subscribing.
     * Each relay runs an independent reconnection loop with exponential backoff.
     */
    suspend fun connect(peerPubkey: String) {
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ownPubkey = peerPubkey.lowercase()

        httpClient = HttpClient {
            install(WebSockets)
            // Connection timeout per attempt
            engine {
                // OkHttp engine: set connect timeout
            }
        }

        val relayList = _relays.value

        // Priority: self-hosted NEO-P2P relays first (they carry the trade feed),
        // then public fallback relays. Connect the custom relays first, wait for
        // one to come up, then open the public relays. If no custom relay can be
        // reached within the timeout, still open the public relays as fallback.
        val customRelays = relayList.filter { isCustomRelay(it.url) }
        val publicRelays = relayList.filterNot { isCustomRelay(it.url) }

        for (relay in customRelays) {
            scope?.launch {
                connectWithBackoff(relay.url)
            }
        }

        // Wait for the first custom relay to connect (up to a timeout), so public
        // relays are only opened as a fallback after NEO-P2P relays are preferred.
        withTimeoutOrNull(RELAY_CONNECT_TIMEOUT_MS) {
            while (scope?.isActive == true) {
                val anyCustomUp = _relays.value.any {
                    isCustomRelay(it.url) && it.isConnected
                }
                if (anyCustomUp) break
                delay(200)
            }
        }

        for (relay in publicRelays) {
            scope?.launch {
                connectWithBackoff(relay.url)
            }
        }
    }

    /**
     * Connect to a single relay with exponential backoff reconnection.
     * Runs until scope is cancelled (disconnect() called).
     */
    private suspend fun connectWithBackoff(relayUrl: String) {
        var attempt = 0
        var backoffMs = RECONNECT_BASE_DELAY_MS

        while (scope?.isActive == true) {
            attempt++
            try {
                val client = httpClient ?: return
                client.webSocket(relayUrl) {
                    // Connected — reset backoff
                    attempt = 0
                    backoffMs = RECONNECT_BASE_DELAY_MS
                    activeSockets.add(this)
                    socketsByRelay[relayUrl] = this
                    Log.d(TAG, "Connected to Nostr relay: $relayUrl (session ${hashCode()})")

                    // Subscribe to trade offers — ONLY on NEO-P2P self-hosted
                    // relays. Public fallback relays (nos.lol, relay.damus.io)
                    // carry arbitrary kind-33333 events from unrelated Nostr
                    // users that are NOT NEO-P2P offers; subscribing there would
                    // flood the feed with garbage offers.
                    if (isCustomRelay(relayUrl)) {
                        val subFilter = buildJsonObject {
                            putJsonArray("kinds") { add(KIND_TRADE_OFFER) }
                            put("limit", 50)
                        }
                        val subMsg = buildJsonArray {
                            add("REQ")
                            add("neop2p-trade-feed")
                            add(subFilter)
                        }
                        send(Frame.Text(Json.encodeToString(JsonElement.serializer(), subMsg)))

                        // Subscribe to NIP-09 deletion events so offers removed on
                        // another NEO-P2P peer also disappear here.
                        val delFilter = buildJsonObject {
                            putJsonArray("kinds") { add(KIND_OFFER_DELETE) }
                            put("limit", 50)
                        }
                        val delMsg = buildJsonArray {
                            add("REQ")
                            add("neop2p-deletions")
                            add(delFilter)
                        }
                        send(Frame.Text(Json.encodeToString(JsonElement.serializer(), delMsg)))

                        // Subscribe to offer status updates so an offer locked by
                        // another peer (acceptance) is marked MATCHED here too.
                        val statusFilter = buildJsonObject {
                            putJsonArray("kinds") { add(KIND_OFFER_STATUS) }
                            put("limit", 50)
                        }
                        val statusMsg = buildJsonArray {
                            add("REQ")
                            add("neop2p-offer-status")
                            add(statusFilter)
                        }
                        send(Frame.Text(Json.encodeToString(JsonElement.serializer(), statusMsg)))

                        // Subscribe to escrow lifecycle events (kind:33337) so
                        // both trade parties converge on the same escrow row
                        // (funding, payment, receipt states) — only on the
                        // self-hosted relays, same as offers.
                        val escrowStatusFilter = buildJsonObject {
                            putJsonArray("kinds") { add(KIND_ESCROW_STATUS) }
                            put("limit", 200)
                        }
                        val escrowStatusMsg = buildJsonArray {
                            add("REQ")
                            add("neop2p-escrow-status")
                            add(escrowStatusFilter)
                        }
                        send(Frame.Text(Json.encodeToString(JsonElement.serializer(), escrowStatusMsg)))

                        // Subscribe to arbitration events (dispute, evidence,
                        // resolution) — only on self-hosted relays, same as the
                        // other NEO-P2P kinds.
                        val arbitrationFilter = buildJsonObject {
                            putJsonArray("kinds") { add(KIND_DISPUTE); add(KIND_EVIDENCE); add(KIND_RESOLUTION) }
                            put("limit", 200)
                        }
                        val arbitrationMsg = buildJsonArray {
                            add("REQ")
                            add("neop2p-arbitration")
                            add(arbitrationFilter)
                        }
                        send(Frame.Text(Json.encodeToString(JsonElement.serializer(), arbitrationMsg)))
                    }

                    // Subscribe to attestations — ONLY on the self-hosted NEO-P2P
                    // relays, same as offers. Kind 33335 is used by unrelated
                    // Nostr apps (WoT vouches) on public relays; parsing their
                    // content as our attestation JSON would just spam warnings.
                    if (isCustomRelay(relayUrl)) {
                        val attestFilter = buildJsonObject {
                            putJsonArray("kinds") { add(KIND_ATTESTATION) }
                            put("limit", 100)
                        }
                        val attestMsg = buildJsonArray {
                            add("REQ")
                            add("neop2p-attestations")
                            add(attestFilter)
                        }
                        send(Frame.Text(Json.encodeToString(JsonElement.serializer(), attestMsg)))
                    }

                    // Update relay status
                    _relays.update { list ->
                        list.map {
                            if (it.url == relayUrl) it.copy(isConnected = true)
                            else it
                        }
                    }

                    // Listen for incoming events
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            handleNostrMessage(relayUrl, frame.readText())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Relay $relayUrl disconnected (attempt $attempt): ${e.message}")
            }

            // Update relay status
            _relays.update { list ->
                list.map {
                    if (it.url == relayUrl) it.copy(isConnected = false)
                    else it
                }
            }
            socketsByRelay.remove(relayUrl)

            // Exponential backoff with jitter before reconnecting
            val jitter = (Math.random() * RECONNECT_JITTER_MS * 2 - RECONNECT_JITTER_MS).toLong()
            delay(backoffMs + jitter)
            backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        }
    }

    /**
     * Handle incoming Nostr events. [relayUrl] identifies which relay the
     * message came from so publish acks can be correlated per-relay.
     */
    private fun handleNostrMessage(relayUrl: String, text: String) {
        try {
            val json = Json.parseToJsonElement(text).jsonArray
            val type = json[0].jsonPrimitive.content

            when (type) {
                "EVENT" -> {
                    val event = json[2].jsonObject
                    val kind = event["kind"]?.jsonPrimitive?.int ?: return
                    if (!verifyEventSignature(event)) {
                        Log.w(TAG, "Rejected event with invalid signature (kind=$kind)")
                        return
                    }
                    when (kind) {
                        KIND_TRADE_OFFER, KIND_TRADE_RESPONSE -> {
                            // Accept the offer. We only subscribe on the self-hosted
                            // NEO-P2P relays (not public Nostr), and every event is
                            // already signature-verified above. The peer gate was
                            // previously isPeerAuthenticated, which broke cross-device
                            // sync: relay-only peers never appear in the auth registry
                            // (it is only populated by direct libp2p sessions / WS
                            // relay announces), so legitimate offers from a second
                            // device were dropped. On a self-hosted relay, a valid
                            // signature is sufficient to trust the offer.
                            scope?.launch { _offers.emit(event) }
                        }
                        KIND_ATTESTATION -> {
                            // kind:33335 — peer attestation. Content is JSON:
                            // {from, target, outcome, volume_sats, timestamp, signature}.
                            // Persist (dedupe via PK IGNORE), remember the signer's
                            // pubkey for later signature verification, and emit.
                            try {
                                val content = event["content"]?.jsonPrimitive?.content ?: return
                                val obj = Json.parseToJsonElement(content).jsonObject
                                val from = obj["from"]?.jsonPrimitive?.content ?: return
                                val target = obj["target"]?.jsonPrimitive?.content ?: return
                                val outcome = obj["outcome"]?.jsonPrimitive?.content ?: return
                                val volume = obj["volume_sats"]?.jsonPrimitive?.long ?: 0L
                                val ts = obj["timestamp"]?.jsonPrimitive?.long ?: 0L
                                val sig = obj["signature"]?.jsonPrimitive?.content ?: return

                                val entity = AttestationEntity(
                                    id = "$from:$target:$ts",
                                    from_peer_id = from,
                                    target_peer_id = target,
                                    outcome = outcome,
                                    volume_sats = volume,
                                    timestamp = ts,
                                    signature_hex = sig
                                )
                                scope?.launch {
                                    try {
                                        // IGNORE-deduped insert: -1 means the row
                                        // already exists (relay replay on every
                                        // reconnect/refresh). Only emit NEW
                                        // attestations — emitting replays would
                                        // re-increment the target's trade count
                                        // on every refresh (ReputationSystem
                                        // counts each processed attestation).
                                        val inserted = attestationDao.insert(entity)
                                        if (inserted == -1L) return@launch
                                        // Remember the signer's pubkey so their
                                        // attestation signatures verify later.
                                        event["pubkey"]?.jsonPrimitive?.content?.let { pub ->
                                            peerDao.updateNostrPubkey(from, pub)
                                        }
                                        _attestations.emit(entity)
                                        Log.d(TAG, "Stored attestation from=$from target=$target outcome=$outcome")
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to persist attestation: ${e.message}")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Malformed attestation event: ${e.message}")
                            }
                        }
                        KIND_OFFER_DELETE -> {
                            // NIP-09: a deletion event references the ids it removes
                            // via "e" tags. When another NEO-P2P peer deletes one of
                            // their offers, we emit those ids so local copies are
                            // removed on this device too. Our OWN deletions are
                            // emitted on [ownDeletions] so the tombstone store
                            // gets backfilled (the relay replays them on every
                            // connect; the local delete may predate this fix).
                            if (event["pubkey"]?.jsonPrimitive?.content?.lowercase() == ownPubkey) {
                                Log.d(TAG, "Ignoring own deletion event")
                                val ownIds = event["tags"]?.jsonArray?.mapNotNull { tag ->
                                    val arr = tag.jsonArray
                                    if (arr.firstOrNull()?.jsonPrimitive?.content == "e")
                                        arr.getOrNull(1)?.jsonPrimitive?.content
                                    else null
                                }?.filter { !it.isNullOrBlank() }.orEmpty()
                                ownIds.forEach { id ->
                                    scope?.launch { _ownDeletions.emit(id) }
                                }
                                return@handleNostrMessage
                            }
                            val deletedIds = event["tags"]?.jsonArray?.mapNotNull { tag ->
                                val arr = tag.jsonArray
                                if (arr.firstOrNull()?.jsonPrimitive?.content == "e")
                                    arr.getOrNull(1)?.jsonPrimitive?.content
                                else null
                            }?.filter { !it.isNullOrBlank() }.orEmpty()
                            deletedIds.forEach { id ->
                                scope?.launch { _deletions.emit(id) }
                            }
                            if (deletedIds.isNotEmpty()) {
                                Log.d(TAG, "Received deletion for ${deletedIds.size} offer(s)")
                            }
                        }
                        KIND_OFFER_STATUS -> {
                            // Offer status/lock update: content holds offer_id and status.
                            try {
                                val content = event["content"]?.jsonPrimitive?.content ?: return
                                val obj = Json.parseToJsonElement(content).jsonObject
                                val oid = obj["offer_id"]?.jsonPrimitive?.content ?: return
                                val status = obj["status"]?.jsonPrimitive?.content ?: return
                                val matchedPeerId = obj["matched_peer_id"]?.jsonPrimitive?.content
                                val buyerBtcAddress = obj["buyer_btc_address"]?.jsonPrimitive?.content
                                val authorPeerId = obj["author_peer_id"]?.jsonPrimitive?.content
                                scope?.launch {
                                    _offerStatusUpdates.emit(
                                        OfferStatusUpdate(oid, status, matchedPeerId, buyerBtcAddress, authorPeerId)
                                    )
                                }
                                Log.d(TAG, "Received status update offer=$oid status=$status matched=$matchedPeerId")
                            } catch (_: Exception) {
                                Log.w(TAG, "Malformed offer status update")
                            }
                        }
                        KIND_ESCROW_STATUS -> {
                            // Escrow lifecycle event (2-party sync). Content:
                            // {escrow_id, status, ts, ...mutable escrow fields}.
                            try {
                                val content = event["content"]?.jsonPrimitive?.content ?: return
                                val obj = Json.parseToJsonElement(content).jsonObject
                                val eid = obj["escrow_id"]?.jsonPrimitive?.content ?: return
                                scope?.launch { _escrowStatusEvents.emit(obj) }
                                Log.d(TAG, "Received escrow status escrow=$eid status=${obj["status"]?.jsonPrimitive?.content}")
                            } catch (_: Exception) {
                                Log.w(TAG, "Malformed escrow status event")
                            }
                        }
                        KIND_DISPUTE -> {
                            // Arbitration: a dispute was opened on an escrow.
                            // Content: {escrow_id, opened_by, reason, opened_at}.
                            try {
                                val content = event["content"]?.jsonPrimitive?.content ?: return
                                val obj = Json.parseToJsonElement(content).jsonObject
                                val eid = obj["escrow_id"]?.jsonPrimitive?.content ?: return
                                scope?.launch { _disputes.emit(obj) }
                                Log.d(TAG, "Received dispute for escrow=$eid")
                            } catch (_: Exception) {
                                Log.w(TAG, "Malformed dispute event")
                            }
                        }
                        KIND_EVIDENCE -> {
                            // Dispute evidence: receipt image + description for an
                            // escrow. Content: {escrow_id, submitter, description,
                            // mime_type, image_base64}. Emitted raw; the arbitrator
                            // feed decodes the base64 image.
                            try {
                                val content = event["content"]?.jsonPrimitive?.content ?: return
                                val obj = Json.parseToJsonElement(content).jsonObject
                                val eid = obj["escrow_id"]?.jsonPrimitive?.content ?: return
                                scope?.launch { _evidence.emit(obj) }
                                Log.d(TAG, "Received evidence for escrow=$eid")
                            } catch (_: Exception) {
                                Log.w(TAG, "Malformed evidence event")
                            }
                        }
                        KIND_RESOLUTION -> {
                            // Arbitration resolution: the arbitrator's decision +
                            // signature. Content: {escrow_id, decision,
                            // arbitrator_sig_hex, notes, decided_at}.
                            try {
                                val content = event["content"]?.jsonPrimitive?.content ?: return
                                val obj = Json.parseToJsonElement(content).jsonObject
                                val eid = obj["escrow_id"]?.jsonPrimitive?.content ?: return
                                scope?.launch { _resolutions.emit(obj) }
                                Log.d(TAG, "Received resolution for escrow=$eid")
                            } catch (_: Exception) {
                                Log.w(TAG, "Malformed resolution event")
                            }
                        }
                    }
                }
                "EOSE" -> Log.d(TAG, "End of stored events")
                "NOTICE" -> Log.w(TAG, "Relay notice: ${json.getOrNull(1)}")
                "OK" -> {
                    val eventId = json.getOrNull(1)?.jsonPrimitive?.content
                    val success = json.getOrNull(2)?.jsonPrimitive?.boolean ?: false
                    val message = json.getOrNull(3)?.jsonPrimitive?.content ?: ""
                    if (success) {
                        Log.d(TAG, "Event $eventId accepted by relay")
                    } else {
                        Log.w(TAG, "Event $eventId rejected by relay: $message")
                    }
                    // Fulfil any publish that is waiting on this event's ack,
                    // correlated to the relay that replied.
                    if (eventId != null) {
                        pendingAcks.remove("$eventId|$relayUrl")?.complete(success)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Nostr message: ${e.message}")
        }
    }

    /**
     * Publish a trade offer to all connected relays.
     *
     * The event is signed with the supplied [privateKeyHex]/[pubkeyHex]. Callers
     * pass a **fresh per-trade key** (see IdentityManager.getNextTradeNostrKeyPair)
     * so offers are not linkable to the identity key or to each other (P0-3).
     * If both are blank, falls back to the long-lived identity key for backward
     * compatibility.
     */
    suspend fun publishTradeOffer(
        privateKeyHex: String,
        pubkeyHex: String,
        offerJson: JsonObject
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Use the caller-supplied per-trade key when provided; otherwise fall
            // back to the identity key. This is the P0-3 privacy boundary.
            val (effectivePriv, effectivePub) =
                if (privateKeyHex.isNotBlank() && pubkeyHex.isNotBlank()) {
                    privateKeyHex to pubkeyHex
                } else {
                    val kp = identityManager.getNostrKeyPair()
                    kp.privateKeyHex to kp.publicKeyHex
                }

            val event = NostrEventSigner.buildSignedEvent(
                kind = KIND_TRADE_OFFER,
                content = Json.encodeToString(JsonElement.serializer(), offerJson),
                pubkey = effectivePub,
                privateKeyHex = effectivePriv
            )

            val confirmed = publishToConnectedRelays(event)

            val eventId = event["id"]?.jsonPrimitive?.content ?: ""
            Log.d(TAG, "Trade offer published & confirmed on ${confirmed.size} relays, id=$eventId")
            Result.success(eventId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish trade offer", e)
            Result.failure(e)
        }
    }

    /**
     * Publish a NIP-09 deletion event for a published offer so the deletion
     * propagates to all other devices subscribed to the relay.
     *
     * @param offerEventId the id of the original offer event (its [TradeOffer.nostrEventId])
     * @param privateKeyHex/pubkeyHex the per-trade key that signed the offer (P0-3)
     */
    suspend fun publishOfferDeletion(
        offerEventId: String,
        privateKeyHex: String = "",
        pubkeyHex: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (offerEventId.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Missing offer event id"))
            }
            val (effectivePriv, effectivePub) =
                if (privateKeyHex.isNotBlank() && pubkeyHex.isNotBlank()) {
                    privateKeyHex to pubkeyHex
                } else {
                    val kp = identityManager.getNostrKeyPair()
                    kp.privateKeyHex to kp.publicKeyHex
                }

            val event = NostrEventSigner.buildSignedEvent(
                kind = KIND_OFFER_DELETE,
                content = "",
                pubkey = effectivePub,
                privateKeyHex = effectivePriv,
                tags = listOf(listOf("e", offerEventId))
            )

            publishToConnectedRelays(event)

            val delId = event["id"]?.jsonPrimitive?.content ?: ""
            Log.d(TAG, "Deletion published for offer event $offerEventId (delete id=$delId)")
            Result.success(delId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish offer deletion", e)
            Result.failure(e)
        }
    }

    /**
     * Publish an offer status update (e.g. accept → MATCHED) so other devices
     * mark the offer locked. Signed with the identity key.
     *
     * @param offerId the local offer id (the offer_id field on the trade event)
     * @param status the new status string (e.g. "MATCHED")
     */
    suspend fun publishOfferStatus(
        offerId: String,
        status: String,
        matchedPeerId: String? = null,
        buyerBtcAddress: String? = null,
        authorPeerId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val kp = identityManager.getNostrKeyPair()
            val content = buildJsonObject {
                put("offer_id", offerId)
                put("status", status)
                matchedPeerId?.let { put("matched_peer_id", it) }
                buyerBtcAddress?.takeIf { it.isNotBlank() }?.let { put("buyer_btc_address", it) }
                authorPeerId?.takeIf { it.isNotBlank() }?.let { put("author_peer_id", it) }
            }.toString()
            val event = NostrEventSigner.buildSignedEvent(
                kind = KIND_OFFER_STATUS,
                content = content,
                pubkey = kp.publicKeyHex,
                privateKeyHex = kp.privateKeyHex
            )
            publishToConnectedRelays(event)
            val id = event["id"]?.jsonPrimitive?.content ?: ""
            Log.d(TAG, "Offer $offerId status=$status published (event=$id)")
            Result.success(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish offer status", e)
            Result.failure(e)
        }
    }

    /**
     * Publish an escrow lifecycle event (kind:33337) so the counterparty
     * device converges its local escrow row (2-party flow). Signed with the
     * identity key. Content: {escrow_id, status, ts, ...mutable fields}.
     */
    suspend fun publishEscrowStatus(
        escrowId: String,
        status: String,
        fields: Map<String, String> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val kp = identityManager.getNostrKeyPair()
            val content = buildJsonObject {
                put("escrow_id", escrowId)
                put("status", status)
                put("ts", System.currentTimeMillis())
                fields.forEach { (k, v) -> put(k, v) }
            }.toString()
            val event = NostrEventSigner.buildSignedEvent(
                kind = KIND_ESCROW_STATUS,
                content = content,
                pubkey = kp.publicKeyHex,
                privateKeyHex = kp.privateKeyHex
            )
            publishToConnectedRelays(event)
            val id = event["id"]?.jsonPrimitive?.content ?: ""
            Log.d(TAG, "Escrow $escrowId status=$status published (event=$id)")
            Result.success(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish escrow status", e)
            Result.failure(e)
        }
    }

    /**
     * Publish a signed attestation (kind:33335) to all connected relays.
     *
     * Content is JSON: {from, target, outcome, volume_sats, timestamp, signature}
     * where signature is the BIP-340 Schnorr signature hex over the canonical
     * attestation string (see ReputationSystem.buildAttestationData). Signed
     * with the identity key; receivers verify against the event pubkey.
     */
    suspend fun publishAttestation(
        fromPeer: String,
        targetPeer: String,
        outcome: String,
        volumeSats: Long,
        timestamp: Long,
        signatureHex: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val kp = identityManager.getNostrKeyPair()
            val content = buildJsonObject {
                put("from", fromPeer)
                put("target", targetPeer)
                put("outcome", outcome)
                put("volume_sats", volumeSats)
                put("timestamp", timestamp)
                put("signature", signatureHex)
            }.toString()
            val event = NostrEventSigner.buildSignedEvent(
                kind = KIND_ATTESTATION,
                content = content,
                pubkey = kp.publicKeyHex,
                privateKeyHex = kp.privateKeyHex
            )
            publishToConnectedRelays(event)
            val id = event["id"]?.jsonPrimitive?.content ?: ""
            Log.d(TAG, "Attestation published (event=$id)")
            Result.success(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish attestation", e)
            Result.failure(e)
        }
    }

    /**
     * Publish a dispute-opened event (kind:33386) so both parties and the
     * arbitrator learn the escrow went to arbitration. Signed with the
     * identity key. Content: {escrow_id, opened_by, reason, opened_at,
     * redeem_script_hex, unsigned_tx_hex} — the redeem script + unsigned
     * payout/refund tx are included so a REMOTE arbitrator can sign the
     * resolution without holding the escrow row locally.
     */
    suspend fun publishDispute(
        escrowId: String,
        openedBy: String,
        reason: String,
        redeemScriptHex: String? = null,
        unsignedTxHex: String? = null,
        depositSats: Long? = null,
        fundingScriptType: String? = null,
        sellerRefundAddress: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val kp = identityManager.getNostrKeyPair()
            val content = buildJsonObject {
                put("escrow_id", escrowId)
                put("opened_by", openedBy)
                put("reason", reason)
                put("opened_at", System.currentTimeMillis())
                redeemScriptHex?.let { put("redeem_script_hex", it) }
                unsignedTxHex?.let { put("psbt_hex", it) }
                // BIP-143 (P2WSH) signing commits the input value, and the
                // sighash differs per script type — the remote arbitrator
                // needs both to produce a valid resolution signature.
                depositSats?.let { put("deposit_sats", it) }
                fundingScriptType?.let { put("funding_script_type", it) }
                // The seller's BTC refund address so a REFUND_TO_SELLER
                // resolution pays the SELLER, not whoever applies it.
                sellerRefundAddress?.takeIf { it.isNotBlank() }?.let { put("seller_refund_address", it) }
            }.toString()
            val event = NostrEventSigner.buildSignedEvent(
                kind = KIND_DISPUTE,
                content = content,
                pubkey = kp.publicKeyHex,
                privateKeyHex = kp.privateKeyHex
            )
            publishToConnectedRelays(event)
            val id = event["id"]?.jsonPrimitive?.content ?: ""
            Log.d(TAG, "Dispute published for escrow=$escrowId (event=$id)")
            Result.success(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish dispute", e)
            Result.failure(e)
        }
    }

    /**
     * Publish dispute evidence (kind:33387): a receipt image (base64) +
     * description for an escrow. The arbitrator feed decodes the image.
     * Content: {escrow_id, submitter, description, mime_type, image_base64}.
     */
    suspend fun publishEvidence(
        escrowId: String,
        submitter: String,
        description: String,
        mimeType: String,
        imageBase64: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val kp = identityManager.getNostrKeyPair()
            val content = buildJsonObject {
                put("escrow_id", escrowId)
                put("submitter", submitter)
                put("description", description)
                put("mime_type", mimeType)
                put("image_base64", imageBase64)
            }.toString()
            val event = NostrEventSigner.buildSignedEvent(
                kind = KIND_EVIDENCE,
                content = content,
                pubkey = kp.publicKeyHex,
                privateKeyHex = kp.privateKeyHex
            )
            publishToConnectedRelays(event)
            val id = event["id"]?.jsonPrimitive?.content ?: ""
            Log.d(TAG, "Evidence published for escrow=$escrowId (event=$id)")
            Result.success(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish evidence", e)
            Result.failure(e)
        }
    }

    /**
     * Publish the arbitrator's resolution (kind:33388) after resolving a
     * dispute. Content: {escrow_id, decision, arbitrator_sig_hex, notes,
     * decided_at}. Parties receive it, apply the status locally, and broadcast
     * the payout/refund with the arbitrator's signature (2-of-3).
     */
    suspend fun publishResolution(
        escrowId: String,
        decision: String,
        arbitratorSigHex: String,
        notes: String?,
        sellerRefundAddress: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val kp = identityManager.getNostrKeyPair()
            val content = buildJsonObject {
                put("escrow_id", escrowId)
                put("decision", decision)
                put("arbitrator_sig_hex", arbitratorSigHex)
                notes?.let { put("notes", it) }
                put("decided_at", System.currentTimeMillis())
                // The seller's BTC refund address so the party applying a
                // REFUND_TO_SELLER resolution refunds to the SELLER, not to
                // their own wallet (pre-v20 bug).
                sellerRefundAddress?.takeIf { it.isNotBlank() }?.let { put("seller_refund_address", it) }
            }.toString()
            val event = NostrEventSigner.buildSignedEvent(
                kind = KIND_RESOLUTION,
                content = content,
                pubkey = kp.publicKeyHex,
                privateKeyHex = kp.privateKeyHex
            )
            publishToConnectedRelays(event)
            val id = event["id"]?.jsonPrimitive?.content ?: ""
            Log.d(TAG, "Resolution published for escrow=$escrowId (event=$id)")
            Result.success(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish resolution", e)
            Result.failure(e)
        }
    }

    /**
     * Publish to all connected relays and wait for a NIP-20 (EVENT/OK) ack on
     * each persistent socket before returning. Returns the set of relays that
     * CONFIRMED storage of the event (so callers know the offer actually
     * propagated to the market, not just that a socket was open).
     */
    private suspend fun publishToConnectedRelays(event: JsonObject, timeoutMs: Long = 5_000L): Set<String> {
        val eventId = event["id"]?.jsonPrimitive?.content ?: ""
        if (eventId.isBlank()) return emptySet()

        val connected = _relays.value.filter { it.isConnected }.map { it.url }
        if (connected.isEmpty()) return emptySet()

        val confirmed = linkedSetOf<String>()
        val jobScope = scope ?: return emptySet()
        val jobs = connected.map { relayUrl ->
            jobScope.launch(Dispatchers.IO) {
                try {
                    // Register the ack BEFORE sending so the OK message cannot
                    // slip through between send and await.
                    val deferred = CompletableDeferred<Boolean>()
                    pendingAcks["$eventId|$relayUrl"] = deferred
                    publishRaw(event, relayUrl)
                    if (withTimeoutOrNull(timeoutMs) { deferred.await() } == true) {
                        synchronized(confirmed) { confirmed.add(relayUrl) }
                        Log.d(TAG, "Relay $relayUrl CONFIRMED offer $eventId")
                    } else {
                        Log.w(TAG, "Relay $relayUrl did not ack offer $eventId within ${timeoutMs}ms")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed publishing to $relayUrl: ${e.message}")
                } finally {
                    pendingAcks.remove("$eventId|$relayUrl")
                }
            }
        }
        jobs.forEach { it.join() }
        return confirmed
    }

    /**
     * Send an EVENT over the persistent connection to [relayUrl]. Unlike the old
     * implementation this does NOT open an ephemeral socket that closes before
     * the relay can persist the event — it reuses the long-lived socket held by
     * [connectWithBackoff] so the relay actually stores and replays it.
     */
    private suspend fun publishRaw(event: JsonObject, relayUrl: String) {
        val socket = socketsByRelay[relayUrl] ?: return
        val msg = buildJsonArray {
            add("EVENT")
            add(event)
        }
        try {
            socket.send(Frame.Text(Json.encodeToString(JsonElement.serializer(), msg)))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send EVENT to $relayUrl: ${e.message}")
        }
    }

    /**
     * Build a properly signed Nostr event per NIP-01. Delegates to the pure
     * [NostrEventSigner] (Android-free, JVM-testable).
     *
     * @param pubkey the x-only pubkey that signs the event (must correspond to [privateKeyHex])
     */
    internal fun buildSignedEvent(
        pubkey: String,
        kind: Int,
        content: String,
        privateKeyHex: String,
        tags: List<List<String>> = emptyList()
    ): JsonObject =
        NostrEventSigner.buildSignedEvent(pubkey, kind, content, privateKeyHex, tags)

    /** Sign a Nostr event ID via BIP-340 Schnorr (delegates to [NostrEventSigner]). */
    private fun nostrSign(eventId: String, privateKeyHex: String): String {
        val sig = NostrEventSigner.sign(eventId, privateKeyHex)
        Log.d(TAG, "Nostr event signed via BIP-340 Schnorr")
        return sig
    }

    /** Verify a Nostr event's BIP-340 Schnorr signature against its pubkey (NIP-01). */
    private fun verifyEventSignature(event: JsonObject): Boolean =
        NostrEventSigner.verifyEventSignature(event)

    /**
     * Add a relay to the relay list.
     */
    suspend fun addRelay(url: String) {
        val existing = _relays.value.find { it.url == url }
        if (existing == null) {
            _relays.update { it + NostrRelay(url) }
        }
    }

    /**
     * Disconnect from all relays.
     */
    suspend fun disconnect() {
        scope?.cancel()
        activeSockets.forEach { socket ->
            try { socket.close() } catch (_: Exception) {}
        }
        activeSockets.clear()
        socketsByRelay.clear()
        pendingAcks.clear()
        httpClient?.close()
        httpClient = null
        _relays.update { list -> list.map { it.copy(isConnected = false) } }
        Log.d(TAG, "Disconnected from all Nostr relays")
    }
}