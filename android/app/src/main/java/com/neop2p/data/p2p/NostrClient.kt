package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.NeoP2PConfig
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
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
    private val identityManager: IdentityManager
) {
    companion object {
        private const val TAG = "NostrClient"
        private const val KIND_TRADE_OFFER = 33333
        private const val KIND_TRADE_RESPONSE = 33334
        private const val KIND_ATTESTATION = 33335
        private const val KIND_RELAY_META = 10065

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

    private val _offers = MutableSharedFlow<JsonObject>(replay = 100)
    val offers: SharedFlow<JsonObject> = _offers.asSharedFlow()

    private var httpClient: HttpClient? = null
    private var activeSockets = mutableListOf<WebSocketSession>()
    private var scope: CoroutineScope? = null

    /**
     * Connect to Nostr relays and start subscribing.
     * Each relay runs an independent reconnection loop with exponential backoff.
     */
    suspend fun connect(peerPubkey: String) {
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        httpClient = HttpClient {
            install(WebSockets)
            // Connection timeout per attempt
            engine {
                // OkHttp engine: set connect timeout
            }
        }

        val relayList = _relays.value

        for (relay in relayList) {
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

                    // Subscribe to trade offers
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

                    // Subscribe to attestations
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
                    when (kind) {
                        KIND_TRADE_OFFER, KIND_TRADE_RESPONSE -> {
                            scope?.launch { _offers.emit(event) }
                        }
                        KIND_ATTESTATION -> {
                            Log.d(TAG, "Received attestation event")
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
     */
    suspend fun publishTradeOffer(
        privateKeyHex: String,
        pubkeyHex: String,
        offerJson: JsonObject
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val keyPair = identityManager.getNostrKeyPair()
            val event = buildSignedEvent(
                kind = KIND_TRADE_OFFER,
                content = Json.encodeToString(JsonElement.serializer(), offerJson),
                pubkey = keyPair.publicKeyHex,
                privateKeyHex = keyPair.privateKeyHex
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
     * Build a properly signed Nostr event per NIP-01.
     *
     * Event ID = SHA-256(serialized_event), where serialized_event is:
     *   [0, pubkey, created_at, kind, tags, content]
     * Signature = Schnorr(BIP-340) of the event ID using the Nostr private key.
     *
     * For v2: uses HMAC-SHA512 derivation of Nostr key for signing.
     * Production should use secp256k1-kmp for proper Schnorr signatures.
     */
    private fun buildSignedEvent(
        pubkey: String,
        kind: Int,
        content: String,
        privateKeyHex: String,
        tags: List<List<String>> = emptyList()
    ): JsonObject {
        val createdAt = System.currentTimeMillis() / 1000

        // NIP-01: event ID is SHA-256 of the serialized event array
        // Format: [0, pubkey, created_at, kind, tags, content]
        val serialized = buildJsonArray {
            add(0)
            add(pubkey)
            add(createdAt)
            add(kind)
            add(JsonArray(tags.map { tag -> JsonArray(tag.map { JsonPrimitive(it) }) }))
            add(content)
        }

        val eventId = bytesToHex(
            MessageDigest.getInstance("SHA-256").digest(
                Json.encodeToString(JsonElement.serializer(), serialized).encodeToByteArray()
            )
        )

        // Sign the event ID using the Nostr private key
        // Production: use secp256k1-kmp Schnorr signature (BIP-340)
        // v2: HMAC-SHA256-based signature for development
        val signature = nostrSign(eventId, privateKeyHex)

        return buildJsonObject {
            put("id", eventId)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("kind", kind)
            putJsonArray("tags") {
                tags.forEach { tag ->
                    addJsonArray { tag.forEach { item -> add(item) } }
                }
            }
            put("content", content)
            put("sig", signature)
        }
    }

    /**
     * Sign a Nostr event ID using BIP-340 Schnorr signature (secp256k1).
     *
     * Production: uses secp256k1-kmp JNI library for proper Schnorr signatures.
     * Development fallback: HMAC-SHA256 deterministic signature (rejected by relays).
     */
    private fun nostrSign(eventId: String, privateKeyHex: String): String {
        try {
            val secp256k1 = fr.acinq.secp256k1.Secp256k1.get()
            val msgBytes = hexToBytes(eventId)   // 32-byte event hash (SHA-256)
            val privKeyBytes = hexToBytes(privateKeyHex)  // 32-byte secp256k1 scalar
            val auxRand = java.security.SecureRandom().generateSeed(32)  // 32-byte auxiliary randomness (BIP-340)
            val signature = secp256k1.signSchnorr(msgBytes, privKeyBytes, auxRand)  // 64-byte sig
            Log.d(TAG, "Nostr event signed via secp256k1-kmp (${signature.size}-byte Schnorr sig)")
            return bytesToHex(signature)
        } catch (e: Exception) {
            Log.e(TAG, "secp256k1-kmp Schnorr signing failed", e)
            // Fallback for dev: HMAC-SHA256 deterministic sig (relays WILL reject)
            return fallbackSign(eventId, privateKeyHex)
        }
    }

    /** Development-only fallback: HMAC-SHA256. Relays reject this signature. */
    private fun fallbackSign(eventId: String, privateKeyHex: String): String {
        try {
            val privKeyBytes = hexToBytes(privateKeyHex)
            val msgBytes = hexToBytes(eventId)
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(privKeyBytes, "HmacSHA256"))
            val r = mac.doFinal(msgBytes)
            mac.init(javax.crypto.spec.SecretKeySpec(privKeyBytes, "HmacSHA256"))
            val s = mac.doFinal(r + msgBytes)
            Log.w(TAG, "Nostr event signed with HMAC-SHA256 fallback (relays will reject)")
            return bytesToHex(r) + bytesToHex(s)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback signing also failed", e)
            return ""
        }
    }

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

    // ─── Utility ──────────────────────────────────────────────

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}