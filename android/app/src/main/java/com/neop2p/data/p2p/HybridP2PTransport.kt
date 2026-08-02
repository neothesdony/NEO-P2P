package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.data.p2p.store.PeerRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val activeTransport: P2PTransport
        get() = if (libp2p.state.value.isRunning && libp2p.connectedPeerIds().isNotEmpty()) {
            libp2p
        } else {
            relay
        }

    override val state: StateFlow<P2PTransport.TransportState>
        get() = activeTransport.state

    override val incomingMessages: SharedFlow<P2PTransport.TransportMessage>
        get() = activeTransport.incomingMessages

    override suspend fun start(): Result<Unit> = coroutineScope {
        val libp2pResult = async { libp2p.start() }
        val relayResult = async { relay.start() }

        val direct = libp2pResult.await()
        val fallback = relayResult.await()

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
            Result.success(Unit)
        }
    }

    override suspend fun stop(): Result<Unit> = coroutineScope {
        val direct = async { libp2p.stop() }
        val fallback = async { relay.stop() }
        direct.await()
        fallback.await()
        Result.success(Unit)
    }

    override suspend fun send(toPeerId: String, data: ByteArray, type: String): Result<Unit> {
        // Try direct libp2p first if the peer is connected.
        if (libp2p.state.value.isRunning && libp2p.connectedPeerIds().contains(toPeerId)) {
            libp2p.send(toPeerId, data, type).onSuccess { return Result.success(Unit) }
        }
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

    override fun isDirect(): Boolean = activeTransport.isDirect()

    /**
     * True if at least one transport is active.
     */
    fun isActive(): Boolean = libp2p.state.value.isRunning || relay.state.value.isRunning
}
