package com.neop2p.data.p2p

import android.util.Log
import io.libp2p.core.Connection
import io.libp2p.core.Host
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.unmarshalPrivateKey
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Identify
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.libp2p.transport.ws.WsTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct libp2p transport manager.
 *
 * Provides TCP and WebSocket transports, NoiseXX security, and Mplex muxer.
 * Uses the BIP-32-derived Ed25519 private key from [IdentityManager] so the
 * PeerID is deterministic.
 *
 * NOTE: Circuit relay v2 / AutoRelay is intentionally omitted for now because
 * the current jvm-libp2p release (1.3.5) has an open bug in relay reservation
 * (PR #503). The hybrid fallback [P2PTransportManager] covers NAT/firewall
 * cases until that stabilizes.
 */
@Singleton
class LibP2PManager @Inject constructor(
    private val identityManager: IdentityManager
) : P2PTransport {

    companion object {
        private const val TAG = "LibP2PManager"
        private const val CHAT_PROTOCOL = "/neop2p/chat/1.0.0"
        private const val FILE_PROTOCOL = "/neop2p/file/1.0.0"
        private const val START_TIMEOUT_MS = 10_000L
    }

    private val _state = MutableStateFlow(P2PTransport.TransportState(transportType = "libp2p"))
    override val state: StateFlow<P2PTransport.TransportState> = _state.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<P2PTransport.TransportMessage>(replay = 64)
    override val incomingMessages: SharedFlow<P2PTransport.TransportMessage> = _incomingMessages.asSharedFlow()

    private var host: Host? = null
    private var scope: CoroutineScope? = null
    private val activeStreams = ConcurrentHashMap<String, Stream>()

    override suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        if (_state.value.isRunning) return@withContext Result.success(Unit)

        try {
            _state.value = P2PTransport.TransportState(transportType = "libp2p")
            scope?.cancel()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            identityManager.getOrCreateIdentity()
            val privateKey = identityManager.getLibp2pPrivateKey()
            val privKey = loadEd25519PrivateKey(privateKey)

            val newHost = createHost(privKey)
            host = newHost

            withTimeout(START_TIMEOUT_MS) {
                newHost.start().await()
            }

            installChatHandler(newHost)
            installFileHandler(newHost)

            val peerId = newHost.peerId.toBase58()
            val listenAddrs = newHost.listenAddresses().map { it.toString() }
            Log.i(TAG, "libp2p host started. peerId=$peerId, listen=$listenAddrs")

            _state.value = P2PTransport.TransportState(
                isRunning = true,
                peerId = peerId,
                connectedPeers = newHost.network.connections.size,
                relayConnected = false,
                transportType = "libp2p"
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start libp2p host", e)
            _state.value = P2PTransport.TransportState(transportType = "libp2p")
            Result.failure(e)
        }
    }

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            activeStreams.clear()
            host?.stop()?.await()
            host = null
            scope?.cancel()
            scope = null
            _state.value = P2PTransport.TransportState(transportType = "libp2p")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop libp2p host", e)
            Result.failure(e)
        }
    }

    override suspend fun send(toPeerId: String, data: ByteArray, type: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val h = host ?: return@withContext Result.failure(IllegalStateException("libp2p host not started"))
            val peerId = try {
                PeerId.fromBase58(toPeerId)
            } catch (e: Exception) {
                return@withContext Result.failure(IllegalArgumentException("Invalid peerId: $toPeerId"))
            }

            try {
                val protocol = if (type == "file") FILE_PROTOCOL else CHAT_PROTOCOL
                val streamPromise = h.newStream<Unit>(listOf(protocol), peerId)
                val stream = withTimeout(10_000L) { streamPromise.stream.await() }
                activeStreams[toPeerId] = stream

                val message = buildEnvelope(type, data)
                val buf = Unpooled.wrappedBuffer(message)
                stream.writeAndFlush(buf)
                stream.close().await()
                activeStreams.remove(toPeerId)

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send to $toPeerId", e)
                activeStreams.remove(toPeerId)
                Result.failure(e)
            }
        }

    override suspend fun publish(topic: String, data: ByteArray): Result<Unit> {
        // libp2p pubsub is not available in jvm-libp2p; emulate by broadcasting to connected peers.
        val peers = connectedPeerIds()
        if (peers.isEmpty()) {
            return Result.failure(IllegalStateException("No connected libp2p peers to publish to"))
        }
        var lastError: Exception? = null
        for (peerId in peers) {
            val payload = "topic:$topic|".toByteArray(StandardCharsets.UTF_8) + data
            send(peerId, payload, "pub").onFailure { lastError = it as? Exception ?: Exception(it) }
        }
        return if (lastError == null) Result.success(Unit) else Result.failure(lastError!!)
    }

    override suspend fun subscribe(topic: String): Result<Unit> {
        // Subscriptions are handled by inbound protocol handlers; no-op here.
        return Result.success(Unit)
    }

    override fun isDirect(): Boolean = true

    fun connectedPeerIds(): List<String> {
        return host?.network?.connections?.mapNotNull { it.secureSession()?.remoteId?.toBase58() } ?: emptyList()
    }

    fun listenAddresses(): List<String> = host?.listenAddresses()?.map { it.toString() } ?: emptyList()

    private fun createHost(privKey: PrivKey): Host {
        return host {
            identity {
                factory = { privKey }
            }
            transports {
                add { upgrader -> TcpTransport(upgrader) }
                add { upgrader -> WsTransport(upgrader) }
            }
            secureChannels {
                add { priv, _ -> NoiseXXSecureChannel(priv) }
            }
            muxers {
                add(StreamMuxerProtocol.Mplex)
            }
            network {
                listen("/ip4/0.0.0.0/tcp/0")
                listen("/ip4/0.0.0.0/tcp/0/ws")
            }
            protocols {
                add(Identify())
            }
        }
    }

    private fun installChatHandler(h: Host) {
        val handler = object : ProtocolMessageHandler<ByteBuf> {
            override fun onMessage(stream: Stream, msg: ByteBuf) {
                val bytes = ByteArray(msg.readableBytes())
                msg.readBytes(bytes)
                scope?.launch {
                    parseEnvelope(bytes)?.let { message ->
                        _incomingMessages.emit(message)
                    }
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        h.addProtocolHandler(ProtocolBinding.createSimple(CHAT_PROTOCOL) { ch ->
            val stream = ch as Stream
            stream.pushHandler(handler)
            CompletableFuture.completedFuture(Unit)
        } as ProtocolBinding<Any>)
    }

    private fun installFileHandler(h: Host) {
        val handler = object : ProtocolMessageHandler<ByteBuf> {
            override fun onMessage(stream: Stream, msg: ByteBuf) {
                val bytes = ByteArray(msg.readableBytes())
                msg.readBytes(bytes)
                scope?.launch {
                    parseEnvelope(bytes)?.let { message ->
                        _incomingMessages.emit(message.copy(type = "file"))
                    }
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        h.addProtocolHandler(ProtocolBinding.createSimple(FILE_PROTOCOL) { ch ->
            val stream = ch as Stream
            stream.pushHandler(handler)
            CompletableFuture.completedFuture(Unit)
        } as ProtocolBinding<Any>)
    }

    private fun buildEnvelope(type: String, data: ByteArray): ByteArray {
        val header = "${type}|${identityManager.getOrCreateIdentity().peerId}|".toByteArray(StandardCharsets.UTF_8)
        return header + data
    }

    private fun parseEnvelope(bytes: ByteArray): P2PTransport.TransportMessage? {
        val str = String(bytes, StandardCharsets.UTF_8)
        val firstPipe = str.indexOf('|')
        if (firstPipe <= 0) return null
        val type = str.substring(0, firstPipe)
        val rest = str.substring(firstPipe + 1)
        val secondPipe = rest.indexOf('|')
        if (secondPipe <= 0) return null
        val fromPeerId = rest.substring(0, secondPipe)
        val data = rest.substring(secondPipe + 1).toByteArray(StandardCharsets.UTF_8)
        return P2PTransport.TransportMessage(
            type = type,
            fromPeerId = fromPeerId,
            toPeerId = _state.value.peerId,
            data = data
        )
    }

    /**
     * Wraps a raw 32-byte Ed25519 seed into a libp2p protobuf private key.
     *
     * Avoids importing from io.libp2p.crypto.keys, which has unresolved
     * Kotlin-package metadata in this AGP/Kotlin combination, and avoids
     * depending on protobuf-java classes that conflict with protobuf-javalite
     * on Android.
     *
     * Protobuf encoding (PrivateKey):
     *   field 1 (type, varint) = KeyType.Ed25519 (1)
     *   field 2 (data, length-delimited) = 32-byte seed
     */
    private fun loadEd25519PrivateKey(seed: ByteArray): PrivKey {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val encoded = encodeVarint(1, 0) +   // field 1, wire type 0 (varint)
                encodeVarint(1) +             // KeyType.Ed25519
                encodeVarint(2, 2) +          // field 2, wire type 2 (length-delimited)
                encodeVarint(seed.size) +
                seed
        return unmarshalPrivateKey(encoded)
    }

    private fun encodeVarint(fieldNumber: Int, wireType: Int): ByteArray {
        return encodeVarint((fieldNumber shl 3) or wireType)
    }

    private fun encodeVarint(value: Int): ByteArray {
        var v = value
        val out = mutableListOf<Byte>()
        while (v > 127) {
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        out.add(v.toByte())
        return out.toByteArray()
    }
}
