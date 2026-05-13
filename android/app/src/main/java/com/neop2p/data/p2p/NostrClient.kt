package com.neop2p.data.p2p

import android.util.Log
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
 * Uses NIP-01 for event publishing/subscription and NIP-65 for relay hints.
 * Trades use custom kind numbers to avoid conflicting with existing Nostr apps.
 *
 * Kind conventions for NEO-P2P:
 * - kind: 33333 — Trade offer (encrypted JSON)
 * - kind: 33334 — Trade response/interest
 * - kind: 33335 — Peer attestation (reputation)
 * - kind: 10065 — NIP-65 relay list metadata
 */
@Singleton
class NostrClient @Inject constructor() {
    companion object {
        private const val TAG = "NostrClient"
        private const val KIND_TRADE_OFFER = 33333
        private const val KIND_TRADE_RESPONSE = 33334
        private const val KIND_ATTESTATION = 33335
        private const val KIND_RELAY_META = 10065
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

    private val _offers = MutableSharedFlow<JsonObject>(replay = 100)
    val offers: SharedFlow<JsonObject> = _offers.asSharedFlow()

    private var httpClient: HttpClient? = null
    private var activeSockets = mutableListOf<WebSocketSession>()
    private var scope: CoroutineScope? = null

    /**
     * Connect to Nostr relays and start subscribing.
     */
    suspend fun connect(peerPubkey: String) {
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        httpClient = HttpClient {
            install(WebSockets)
        }

        val relayList = _relays.value

        for (relay in relayList) {
            scope?.launch {
                try {
                    val client = httpClient ?: return@launch
                    client.webSocket(relay.url) {
                        activeSockets.add(this)

                        // Send subscription filter for trade offers
                        val subFilter = buildJsonObject {
                            putJsonArray("kinds") { add(KIND_TRADE_OFFER) }
                            put("limit", 50)
                        }
                        val subMsg = buildJsonArray {
                            add("REQ")
                            add("neop2p-trade-feed")
                            add(subFilter)
                        }
                        send(Frame.Text(Json.encodeToString(subMsg.let { it })))

                        // Also subscribe to attestations
                        val attestFilter = buildJsonObject {
                            putJsonArray("kinds") { add(KIND_ATTESTATION) }
                            put("limit", 100)
                        }
                        val attestMsg = buildJsonArray {
                            add("REQ")
                            add("neop2p-attestations")
                            add(attestFilter)
                        }
                        send(Frame.Text(Json.encodeToString(attestMsg.let { it })))

                        // Update relay status
                        _relays.update { list ->
                            list.map {
                                if (it.url == relay.url) it.copy(isConnected = true)
                                else it
                            }
                        }

                        Log.d(TAG, "Connected to Nostr relay: ${relay.url}")

                        // Listen for incoming events
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                handleNostrMessage(text)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to connect to relay ${relay.url}: ${e.message}")
                    _relays.update { list ->
                        list.map {
                            if (it.url == relay.url) it.copy(isConnected = false)
                            else it
                        }
                    }
                }
            }
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

                    when (kind) {
                        KIND_TRADE_OFFER, KIND_TRADE_RESPONSE -> {
                            scope?.launch { _offers.emit(event) }
                        }
                        KIND_ATTESTATION -> {
                            // Handle reputation attestation
                            Log.d(TAG, "Received attestation event")
                        }
                    }
                }
                "EOSE" -> Log.d(TAG, "End of stored events")
                "NOTICE" -> Log.w(TAG, "Relay notice: ${json.getOrNull(1)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Nostr message: ${e.message}")
        }
    }

    /**
     * Publish a trade offer to all connected relays.
     */
    suspend fun publishTradeOffer(
        privateKeyHex: String,
        pubkeyHex: String,
        offerJson: JsonObject
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val event = buildSignedEvent(
                pubkey = pubkeyHex,
                kind = KIND_TRADE_OFFER,
                content = Json.encodeToString(offerJson),
                privateKeyHex = privateKeyHex
            )

            val eventJson = Json.encodeToString(event)
            val msg = buildJsonArray {
                add("EVENT")
                add(event)
            }

            for (relay in _relays.value.filter { it.isConnected }) {
                try {
                    // Re-send via WebSocket
                    publishRaw(event, relay.url)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to publish to ${relay.url}: ${e.message}")
                }
            }

            Log.d(TAG, "Trade offer published to ${_relays.value.count { it.isConnected }} relays")
            Result.success(event["id"]?.jsonPrimitive?.content ?: "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish trade offer", e)
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
                send(Frame.Text(Json.encodeToString(msg.let { it })))
            }
        } catch (_: Exception) {}
    }

    /**
     * Build a signed Nostr event (basic implementation).
     * Full NIP-01 signing using secp256k1 coming in Phase 2.
     */
    private fun buildSignedEvent(
        pubkey: String,
        kind: Int,
        content: String,
        privateKeyHex: String,
        tags: List<List<String>> = emptyList()
    ): JsonObject {
        val createdAt = System.currentTimeMillis() / 1000

        return buildJsonObject {
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("kind", kind)
            put("content", content)
            putJsonArray("tags") {
                tags.forEach { tag ->
                    addJsonArray {
                        tag.forEach { item -> add(item) }
                    }
                }
            }
            put("id", "placeholder_id")      // Will be replaced with real hash
            put("sig", "placeholder_sig")    // Will be replaced with real signature
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
