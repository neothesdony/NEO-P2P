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
    // the accepting peer's id so the offer creator knows WHO matched.
    private val _offerStatusUpdates = MutableSharedFlow<Triple<String, String, String?>>(replay = 100)
    val offerStatusUpdates: SharedFlow<Triple<String, String, String?>> = _offerStatusUpdates.asSharedFlow()

    // Verified attestations (kind:33335) received from the relay, emitted after
    // persistence. Consumers (reputation, profile) use this instead of the raw
    // event so they only ever see signature-verified data.
    private val _attestations = MutableSharedFlow<AttestationEntity>(replay = 100)
    val attestations: SharedFlow<AttestationEntity> = _attestations.asSharedFlow()

    private var httpClient: HttpClient? = null
    private var activeSockets = mutableListOf<WebSocketSession>()
    private var scope: CoroutineScope? = null

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
                            handleNostrMessage(frame.readText())
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

            // Exponential backoff with jitter before reconnecting
            val jitter = (Math.random() * RECONNECT_JITTER_MS * 2 - RECONNECT_JITTER_MS).toLong()
            delay(backoffMs + jitter)
            backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        }
    }

    /**
     * Handle incoming Nostr events.
     */
    private fun handleNostrMessage(text: String) {
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
                                        attestationDao.insert(entity)
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
                                scope?.launch { _offerStatusUpdates.emit(Triple(oid, status, matchedPeerId)) }
                                Log.d(TAG, "Received status update offer=$oid status=$status matched=$matchedPeerId")
                            } catch (_: Exception) {
                                Log.w(TAG, "Malformed offer status update")
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

            val eventJson = Json.encodeToString(JsonElement.serializer(), event)
            val msg = buildJsonArray {
                add("EVENT")
                add(event)
            }

            for (relay in _relays.value.filter { it.isConnected }) {
                try {
                    publishRaw(event, relay.url)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to publish to ${relay.url}: ${e.message}")
                }
            }

            val eventId = event["id"]?.jsonPrimitive?.content ?: ""
            Log.d(TAG, "Trade offer published to ${_relays.value.count { it.isConnected }} relays, id=$eventId")
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

            for (relay in _relays.value.filter { it.isConnected }) {
                try {
                    publishRaw(event, relay.url)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to publish deletion to ${relay.url}: ${e.message}")
                }
            }

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
        matchedPeerId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val kp = identityManager.getNostrKeyPair()
            val content = buildJsonObject {
                put("offer_id", offerId)
                put("status", status)
                matchedPeerId?.let { put("matched_peer_id", it) }
            }.toString()
            val event = NostrEventSigner.buildSignedEvent(
                kind = KIND_OFFER_STATUS,
                content = content,
                pubkey = kp.publicKeyHex,
                privateKeyHex = kp.privateKeyHex
            )
            for (relay in _relays.value.filter { it.isConnected }) {
                try {
                    publishRaw(event, relay.url)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to publish status to ${relay.url}: ${e.message}")
                }
            }
            val id = event["id"]?.jsonPrimitive?.content ?: ""
            Log.d(TAG, "Offer $offerId status=$status published (event=$id)")
            Result.success(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish offer status", e)
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
            for (relay in _relays.value.filter { it.isConnected }) {
                try {
                    publishRaw(event, relay.url)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to publish attestation to ${relay.url}: ${e.message}")
                }
            }
            val id = event["id"]?.jsonPrimitive?.content ?: ""
            Log.d(TAG, "Attestation published (event=$id)")
            Result.success(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish attestation", e)
            Result.failure(e)
        }
    }

    /**
     * Publish to a specific relay via raw JSON.
     */
    private suspend fun publishRaw(event: JsonObject, relayUrl: String) {
        try {
            val client = httpClient ?: return
            client.webSocket(relayUrl) {
                val msg = buildJsonArray {
                    add("EVENT")
                    add(event)
                }
                send(Frame.Text(Json.encodeToString(JsonElement.serializer(), msg)))
            }
        } catch (_: Exception) {}
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
        httpClient?.close()
        httpClient = null
        _relays.update { list -> list.map { it.copy(isConnected = false) } }
        Log.d(TAG, "Disconnected from all Nostr relays")
    }
}