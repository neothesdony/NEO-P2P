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
import java.net.NetworkInterface
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
    private val identityManager: IdentityManager,
    private val peerRegistry: com.neop2p.data.p2p.store.PeerRegistry
) : P2PTransport {

    companion object {
        private const val TAG = "LibP2PManager"
        private const val CHAT_PROTOCOL = "/neop2p/chat/1.0.0"
        private const val FILE_PROTOCOL = "/neop2p/file/1.0.0"
        private const val START_TIMEOUT_MS = 10_000L

        /** Replaces `/ip4/0.0.0.0/` with `/ip4/<lanIp>/`; falls back to the raw
         *  address when no site-local IPv4 exists (keeps the /p2p suffix intact). */
        internal fun withLanIp(addr: String, lanIp: String?): String {
            if (lanIp == null) return addr
            return addr.replace("/ip4/0.0.0.0/", "/ip4/$lanIp/")
        }
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

    /**
     * Dial-able multiaddrs for this host: listen addresses with the local LAN
     * IP substituted for the wildcard, plus the HostImpl-appended
     * `/p2p/<peerId>` suffix. E.g. `/ip4/192.168.1.5/tcp/41234/p2p/12D3KooW...`
     * and `/ip4/192.168.1.5/tcp/41235/ws/p2p/12D3KooW...`.
     *
     * The raw bound address is `0.0.0.0:<port>` (Netty wildcard), which no
     * remote peer can dial — the site-local IPv4 is substituted instead. These
     * addresses are only usable by same-LAN peers (the flow-test topology);
     * internet peers are covered by the circuit relay (Phase 2).
     */
    fun currentMultiaddrs(): List<String> {
        val h = host ?: return emptyList()
        return h.listenAddresses()
            .map { it.toString() }
            .map { addr -> withLanIp(addr, localIpv4Address()) }
    }

    private fun localIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is java.net.Inet4Address && a.isSiteLocalAddress) {
                        return a.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

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
                val remoteId = stream.connection.secureSession()?.remoteId?.toBase58()
                    ?: run {
                        Log.w(TAG, "Dropping chat message from unauthenticated (non-secure) connection")
                        return
                    }
                scope?.launch {
                    parseEnvelope(bytes)?.let { message ->
                        peerRegistry.recordPeerSeen(remoteId, authenticated = true)
                        _incomingMessages.emit(
                            message.copy(
                                fromPeerId = remoteId,
                                authenticated = true
                            )
                        )
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
                val remoteId = stream.connection.secureSession()?.remoteId?.toBase58()
                    ?: run {
                        Log.w(TAG, "Dropping file message from unauthenticated (non-secure) connection")
                        return
                    }
                scope?.launch {
                    parseEnvelope(bytes)?.let { message ->
                        peerRegistry.recordPeerSeen(remoteId, authenticated = true)
                        _incomingMessages.emit(
                            message.copy(
                                type = "file",
                                fromPeerId = remoteId,
                                authenticated = true
                            )
                        )
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
        // Envelope is "type|data" only — the from field is intentionally NOT
        // embedded because the sender controls it and it is forgeable. The
        // authenticated remote identity comes from the secure session instead.
        val header = "$type|".toByteArray(StandardCharsets.UTF_8)
        return header + data
    }

    private fun parseEnvelope(bytes: ByteArray): P2PTransport.TransportMessage? {
        val str = String(bytes, StandardCharsets.UTF_8)
        val firstPipe = str.indexOf('|')
        if (firstPipe <= 0) return null
        val type = str.substring(0, firstPipe)
        val data = str.substring(firstPipe + 1).toByteArray(StandardCharsets.UTF_8)
        return P2PTransport.TransportMessage(
            type = type,
            fromPeerId = "",
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
