package com.neop2p.data.p2p

import android.content.Context
import android.util.Log
import io.libp2p.core.*
import io.libp2p.core.crypto.*
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.pubsub.*
import io.libp2p.protocol.circuit.*
import io.libp2p.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the java-libp2p host for peer-to-peer networking.
 *
 * Handles:
 * - Host initialization and identity binding
 * - Circuit Relay v2 for NAT traversal
 * - GossipSub for reputation attestations
 * - Direct streams for E2EE chat transport
 * - Connection lifecycle management
 */
@Singleton
class LibP2PManager @Inject constructor(
    private val identityManager: IdentityManager,
    private val context: Context
) {
    companion object {
        private const val TAG = "LibP2PManager"
        private const val PROTOCOL_CHAT = "/neop2p/chat/1.0.0"
        private const val PROTOCOL_KEY_EXCHANGE = "/neop2p/x3dh/1.0.0"
        private const val LISTEN_PORT = 0  // OS-assigned port (NAT-friendly)
    }

    data class ConnectionState(
        val isRunning: Boolean = false,
        val peerId: String = "",
        val connectedPeers: Int = 0,
        val listenAddresses: List<String> = emptyList()
    )

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var host: Host? = null
    private var pubsub: Topic? = null
    private var scope: CoroutineScope? = null

    // ─── Lifecycle ────────────────────────────────────────────

    /**
     * Starts the libp2p host. Must be called from a coroutine context.
     */
    suspend fun start(): Result<Host> = withContext(Dispatchers.IO) {
        try {
            val identity = identityManager.getOrCreateIdentity()
            val privKey = convertToLibp2pKey(identity.privateKey)

            val listenAddr = Multiaddr("/ip4/0.0.0.0/tcp/$LISTEN_PORT")

            val host = Host.builder()
                .identity(privKey)
                .addListener(listenAddr)
                .protocol(listOf(
                    ChatProtocol(PROTOCOL_CHAT),
                    ChatProtocol(PROTOCOL_KEY_EXCHANGE)
                ))
                .transport(listOf(
                    TcpTransport(),
                    WebSocketTransport()
                ))
                .relay(RelayConfig.builder()
                    .enableRelayHop(true)     // Can route through other peers
                    .enableAutoRelay(true)     // Auto-discover relays
                    .build()
                )
                .build()

            host.start().get(15, TimeUnit.SECONDS)

            // Connect to default circuit relays
            for (relayAddr in NeoP2PConfig.DEFAULT_LIBP2P_RELAYS) {
                try {
                    host.connect(Multiaddr(relayAddr)).get(10, TimeUnit.SECONDS)
                    Log.d(TAG, "Connected to relay: $relayAddr")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to connect to relay $relayAddr: ${e.message}")
                }
            }

            this.host = host
            _connectionState.value = ConnectionState(
                isRunning = true,
                peerId = identity.peerId,
                connectedPeers = host.getNetwork().getPeers().size,
                listenAddresses = host.getListenAddresses().map { it.toString() + "/p2p/" + identity.peerId }
            )

            Log.d(TAG, "libp2p host started: ${identity.peerId}")
            Result.success(host)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start libp2p host", e)
            _connectionState.value = _connectionState.value.copy(isRunning = false)
            Result.failure(e)
        }
    }

    /**
     * Stops the libp2p host gracefully.
     */
    suspend fun stop() {
        withContext(Dispatchers.IO) {
            scope?.cancel()
            try {
                host?.stop()?.get(5, TimeUnit.SECONDS)
            } catch (_: Exception) {}
            host = null
            _connectionState.value = ConnectionState()
            Log.d(TAG, "libp2p host stopped")
        }
    }

    // ─── Direct Connection ─────────────────────────────────────

    /**
     * Opens a direct P2P stream to a peer for E2EE chat.
     */
    suspend fun openChatStream(peerId: String): Result<Stream> = withContext(Dispatchers.IO) {
        try {
            val h = host ?: return@withContext Result.failure(Exception("Host not started"))
            val peer = PeerId.fromBase58(peerId)
            val stream = h.createStream(peer, PROTOCOL_CHAT).get(15, TimeUnit.SECONDS)
            Log.d(TAG, "Chat stream opened to $peerId")
            Result.success(stream)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open chat stream to $peerId", e)
            Result.failure(e)
        }
    }

    /**
     * Opens a stream for X3DH key exchange (libsignal handshake).
     */
    suspend fun openKeyExchangeStream(peerId: String): Result<Stream> = withContext(Dispatchers.IO) {
        try {
            val h = host ?: return@withContext Result.failure(Exception("Host not started"))
            val peer = PeerId.fromBase58(peerId)
            val stream = h.createStream(peer, PROTOCOL_KEY_EXCHANGE).get(15, TimeUnit.SECONDS)
            Result.success(stream)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── GossipSub (Reputation) ───────────────────────────────

    /**
     * Subscribe to a topic for gossip-based reputation propagation.
     */
    suspend fun subscribeToTopic(topic: String, onMessage: (ByteArray) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val h = host ?: return@withContext Result.failure(Exception("Host not started"))
            val gossipSub = Topic.from(topic)
            gossipSub.subscribe { message ->
                onMessage(message.data)
            }
            pubsub = gossipSub
            Log.d(TAG, "Subscribed to topic: $topic")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Publish a message to a GossipSub topic.
     */
    suspend fun publishToTopic(topic: String, data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            pubsub?.publish(data)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── Key Conversion ───────────────────────────────────────

    private fun convertToLibp2pKey(javaPrivateKey: java.security.PrivateKey): PrivKey {
        // Android KeyStore-backed Ed25519 key wrapped as libp2p PrivKey
        // Uses the raw key material for libp2p identity
        val encoded = javaPrivateKey.encoded
        return KeyKt.unmarshalPrivateKey(encoded)
    }

    // ─── DHT Peer Discovery ─────────────────────────────────

    /**
     * Find a peer's multiaddrs on the DHT by their PeerID.
     */
    suspend fun findPeer(peerId: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val h = host ?: return@withContext Result.failure(Exception("Host not started"))
            val peer = PeerId.fromBase58(peerId)
            val addrs = h.getNetwork().findPeer(peer)
            Result.success(addrs.map { it.toString() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// Simple echo protocol handler for chat streams
class ChatProtocol(private val protocolId: String) : ProtocolBinding<Stream> {
    override fun getProtocolDescriptor() = object : ProtocolDescriptor {
        override fun getProtocolId() = protocolId
    }

    override fun createHandler(stream: Stream, queue: StreamPromise<Stream>) {
        queue.success(stream)
    }
}
