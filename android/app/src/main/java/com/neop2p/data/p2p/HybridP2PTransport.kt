package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.data.p2p.store.PeerRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid P2P transport: tries direct libp2p first, falls back to WebSocket relay.
 *
 * This is the production P2P strategy for NEO-P2P:
 *   - libp2p gives true direct peer connections where possible.
 *   - The WebSocket relay covers strict NAT/firewall cases.
 *
 * The unified [P2PTransport] interface lets Signal, chat, and escrow code stay
 * transport-agnostic.
 *
 * State and incoming messages are owned flows, merged from both child transports.
 * They are stable across transport handoff (libp2p <-> relay), unlike delegating
 * to a single "active" transport whose flow object changes when the active
 * transport flips.
 */
@Singleton
class HybridP2PTransport @Inject constructor(
    private val libp2p: LibP2PManager,
    private val relay: P2PTransportManager,
    private val peerRegistry: PeerRegistry
) : P2PTransport {

    companion object {
        private const val TAG = "HybridP2PTransport"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var mergerJob: Job? = null
    @Volatile private var stateJob: Job? = null

    private val _state = MutableStateFlow(P2PTransport.TransportState(transportType = "hybrid"))
    override val state: StateFlow<P2PTransport.TransportState> = _state.asStateFlow()

    private val _incomingMessages =
        MutableSharedFlow<P2PTransport.TransportMessage>(replay = 64)
    override val incomingMessages: SharedFlow<P2PTransport.TransportMessage> = _incomingMessages.asSharedFlow()

    override suspend fun start(): Result<Unit> = coroutineScope {
        val libp2pResult = async { libp2p.start() }
        val relayResult = async { relay.start() }

        val direct = libp2pResult.await()
        val fallback = relayResult.await()

        // (Re)start the merger and the state collector for the singleton lifetime.
        startMergeAndStateCollectors()

        if (direct.isFailure && fallback.isFailure) {
            val err = Exception("Both libp2p and relay transports failed")
            direct.exceptionOrNull()?.let { err.addSuppressed(it) }
            fallback.exceptionOrNull()?.let { err.addSuppressed(it) }
            Result.failure(err)
        } else {
            if (direct.isFailure) {
                Log.w(TAG, "libp2p direct start failed, using relay fallback: ${direct.exceptionOrNull()?.message}")
            }
            if (fallback.isFailure) {
                Log.w(TAG, "relay start failed, using libp2p only: ${fallback.exceptionOrNull()?.message}")
            }
            updateCompositeState()
            Result.success(Unit)
        }
    }

    override suspend fun stop(): Result<Unit> = coroutineScope {
        mergerJob?.cancel()
        mergerJob = null
        stateJob?.cancel()
        stateJob = null

        val direct = async { libp2p.stop() }
        val fallback = async { relay.stop() }
        direct.await()
        fallback.await()

        _state.value = P2PTransport.TransportState(transportType = "hybrid")
        Result.success(Unit)
    }

    /**
     * Launches (or restarts) the jobs that forward child messages into the owned
     * [incomingMessages] flow and keep the composite [state] up to date.
     */
    private fun startMergeAndStateCollectors() {
        mergerJob?.cancel()
        mergerJob = scope.launch {
            merge(libp2p.incomingMessages, relay.incomingMessages).collect {
                _incomingMessages.emit(it)
            }
        }

        stateJob?.cancel()
        stateJob = scope.launch {
            merge(libp2p.state, relay.state).collect { updateCompositeState() }
        }
    }

    /**
     * Derives the composite hybrid state from both children's live state.
     */
    private fun updateCompositeState() {
        val libRunning = libp2p.state.value.isRunning
        val relayRunning = relay.state.value.isRunning
        _state.value = P2PTransport.TransportState(
            isRunning = libRunning || relayRunning,
            peerId = libp2p.state.value.peerId.ifEmpty { relay.state.value.peerId },
            connectedPeers = peerRegistry.connectedPeerCount(),
            relayConnected = relayRunning,
            transportType = when {
                libRunning -> "libp2p"
                relayRunning -> "ws-relay"
                else -> "hybrid"
            }
        )
    }

    override suspend fun send(toPeerId: String, data: ByteArray, type: String): Result<Unit> {
        // 1. Already connected direct — send over libp2p.
        if (libp2p.state.value.isRunning && libp2p.connectedPeerIds().contains(toPeerId)) {
            libp2p.send(toPeerId, data, type).onSuccess { return Result.success(Unit) }
        }
        // 2. Not connected: try to establish a libp2p connection. Direct
        //    addrs are tried first when known; the circuit relay is ALWAYS
        //    attempted as the last resort (the go relay routes by peerId, so
        //    even a peer with no advertised addrs — or unroutable ones like
        //    an emulator's 10.0.2.x NAT IP — is reachable via /p2p-circuit).
        if (libp2p.state.value.isRunning) {
            val addrs = peerRegistry.multiaddrsOf(toPeerId)
            libp2p.dial(toPeerId, addrs).onSuccess {
                libp2p.send(toPeerId, data, type).onSuccess { return Result.success(Unit) }
            }.onFailure { Log.d(TAG, "Dial failed, falling back: ${it.message}") }
        }
        // 3. Fall back to the WS relay.
        return relay.send(toPeerId, data, type)
    }

    override suspend fun publish(topic: String, data: ByteArray): Result<Unit> {
        // Publish via both transports to maximize reach.
        val direct = libp2p.publish(topic, data)
        val fallback = relay.publish(topic, data)
        return if (direct.isSuccess || fallback.isSuccess) {
            Result.success(Unit)
        } else {
            fallback
        }
    }

    override suspend fun subscribe(topic: String): Result<Unit> {
        val direct = libp2p.subscribe(topic)
        val fallback = relay.subscribe(topic)
        return if (direct.isSuccess || fallback.isSuccess) {
            Result.success(Unit)
        } else {
            fallback
        }
    }

    override fun isDirect(): Boolean =
        libp2p.state.value.isRunning && libp2p.connectedPeerIds().isNotEmpty()

    /**
     * True if at least one transport is active.
     */
    fun isActive(): Boolean = libp2p.state.value.isRunning || relay.state.value.isRunning
}
