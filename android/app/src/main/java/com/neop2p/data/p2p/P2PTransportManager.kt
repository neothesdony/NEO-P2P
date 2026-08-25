package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.NeoP2PConfig
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
 * WebSocket relay fallback transport for NEO-P2P.
 *
 * Used by [HybridP2PTransport] when direct libp2p connections are not possible
 * (strict NAT, firewall, no public multiaddrs known yet).
 *
 * JSON protocol: { "type": "msg|sub|pub", "from": peerId, "to": peerId,
 *                 "topic": "...", "data": "..." }
 */
@Singleton
class P2PTransportManager @Inject constructor(
    private val identityManager: IdentityManager,
    private val peerRegistry: PeerRegistry
) : P2PTransport {

    companion object {
        private const val TAG = "P2PTransport"
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_MAX_MS = 60_000L
    }

    private val _connectionState = MutableStateFlow(P2PTransport.TransportState(transportType = "ws-relay"))
    override val state: StateFlow<P2PTransport.TransportState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<P2PTransport.TransportMessage>(replay = 64)
    override val incomingMessages: SharedFlow<P2PTransport.TransportMessage> = _incomingMessages.asSharedFlow()

    private var relaySession: WebSocketSession? = null
    private var scope: CoroutineScope? = null
    private var httpClient: HttpClient? = null

    override suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        if (_connectionState.value.isRunning) return@withContext Result.success(Unit)

        try {
            scope?.cancel()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            httpClient = HttpClient {
                install(WebSockets)
            }

            val identity = identityManager.getOrCreateIdentity()
            val myPeerId = identity.peerId

            scope?.launch {
                connectToRelayWithBackoff(myPeerId)
            }

            _connectionState.value = P2PTransport.TransportState(
                isRunning = true,
                peerId = myPeerId,
                connectedPeers = 0,
                relayConnected = false,
                transportType = "ws-relay"
            )

            Log.d(TAG, "P2P relay transport started: $myPeerId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start P2P relay transport", e)
            Result.failure(e)
        }
    }

    override fun isDirect(): Boolean = false

    /**
     * Connect to the relay server with exponential backoff.
     */
    private suspend fun connectToRelayWithBackoff(myPeerId: String) {
        var attempt = 0
        var backoffMs = RECONNECT_BASE_MS

        while (scope?.isActive == true) {
            attempt++
            try {
                val client = httpClient ?: return
                client.webSocket(com.neop2p.BuildConfig.P2P_RELAY_URL) {
                    relaySession = this
                    _connectionState.update { it.copy(relayConnected = true) }
                    Log.d(TAG, "Connected to P2P relay (attempt $attempt)")

                    // Announce our presence
                    val announce = buildJsonObject {
                        put("type", JsonPrimitive("announce"))
                        put("peerId", JsonPrimitive(myPeerId))
                    }
                    send(Frame.Text(Json.encodeToString(JsonElement.serializer(), announce)))

                    // Reset backoff on successful connection
                    attempt = 0
                    backoffMs = RECONNECT_BASE_MS

                    // Listen for incoming messages
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            handleRelayMessage(frame.readText())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Relay disconnected (attempt $attempt): ${e.message}")
            }

            relaySession = null
            _connectionState.update { it.copy(relayConnected = false) }

            // Exponential backoff with jitter
            val jitter = (Math.random() * 1000 - 500).toLong()
            delay(backoffMs + jitter)
            backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        }
    }

    /**
     * Handle an incoming message from the relay.
     */
    private fun handleRelayMessage(text: String) {
        try {
            val json = Json.parseToJsonElement(text).jsonObject
            val type = json["type"]?.jsonPrimitive?.content ?: return

            when (type) {
                "message" -> {
                    val fromPeer = json["from"]?.jsonPrimitive?.content ?: return
                    val msgType = json["msgType"]?.jsonPrimitive?.content ?: "chat"
                    val dataHex = json["data"]?.jsonPrimitive?.content ?: return
                    val data = hexToBytes(dataHex)

                    scope?.launch {
                        _incomingMessages.emit(P2PTransport.TransportMessage(
                            type = msgType,
                            fromPeerId = fromPeer,
                            toPeerId = _connectionState.value.peerId,
                            data = data,
                            authenticated = false  // WS relay 'from' is unauthenticated echo
                        ))
                    }

                    peerRegistry.recordPeerSeen(fromPeer, authenticated = false)
                    _connectionState.update {
                        it.copy(connectedPeers = peerRegistry.connectedPeerCount())
                    }
                }
                "peer_list" -> {
                    val peers = json["peers"]?.jsonArray
                        ?.map { it.jsonPrimitive.content }
                        ?: emptyList()
                    peers.forEach { peerRegistry.recordPeerSeen(it, authenticated = false) }
                    _connectionState.update {
                        it.copy(connectedPeers = peerRegistry.connectedPeerCount())
                    }
                }
                "error" -> {
                    Log.w(TAG, "Relay error: ${json["message"]?.jsonPrimitive?.content}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse relay message: ${e.message}")
        }
    }

    override suspend fun send(toPeerId: String, data: ByteArray, type: String): Result<Unit> {
        val session = relaySession ?: return Result.failure(Exception("Not connected to relay"))
        return try {
            val msg = buildJsonObject {
                put("type", JsonPrimitive("send"))
                put("to", JsonPrimitive(toPeerId))
                put("msgType", JsonPrimitive(type))
                put("data", JsonPrimitive(bytesToHex(data)))
            }
            session.send(Frame.Text(Json.encodeToString(JsonElement.serializer(), msg)))
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send to $toPeerId", e)
            Result.failure(e)
        }
    }

    override suspend fun subscribe(topic: String): Result<Unit> {
        val session = relaySession ?: return Result.failure(Exception("Not connected to relay"))
        return try {
            val msg = buildJsonObject {
                put("type", JsonPrimitive("subscribe"))
                put("topic", JsonPrimitive(topic))
            }
            session.send(Frame.Text(Json.encodeToString(JsonElement.serializer(), msg)))
            Log.d(TAG, "Subscribed to topic: $topic")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to $topic", e)
            Result.failure(e)
        }
    }

    override suspend fun publish(topic: String, data: ByteArray): Result<Unit> {
        val session = relaySession ?: return Result.failure(Exception("Not connected to relay"))
        return try {
            val msg = buildJsonObject {
                put("type", JsonPrimitive("publish"))
                put("topic", JsonPrimitive(topic))
                put("data", JsonPrimitive(bytesToHex(data)))
            }
            session.send(Frame.Text(Json.encodeToString(JsonElement.serializer(), msg)))
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish to $topic", e)
            Result.failure(e)
        }
    }

    /**
     * Find a peer by ID. Returns their known addresses if any.
     */
    suspend fun findPeer(peerId: String): Result<List<String>> {
        val session = relaySession ?: return Result.success(emptyList())
        return try {
            val msg = buildJsonObject {
                put("type", JsonPrimitive("find_peer"))
                put("peerId", JsonPrimitive(peerId))
            }
            session.send(Frame.Text(Json.encodeToString(JsonElement.serializer(), msg)))
            Result.success(emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find peer $peerId", e)
            Result.success(emptyList())
        }
    }

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            relaySession?.close()
            relaySession = null
            scope?.cancel()
            httpClient?.close()
            httpClient = null
            _connectionState.value = P2PTransport.TransportState(transportType = "ws-relay")
            Log.d(TAG, "P2P relay transport stopped")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop P2P relay transport", e)
            Result.failure(e)
        }
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
