package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.p2p.protocol.AppMessage
import com.neop2p.data.p2p.protocol.EnvelopeCodec
import com.neop2p.data.p2p.queue.OfflineQueue
import com.neop2p.data.p2p.routing.ChatRouter
import com.neop2p.data.p2p.routing.OfferRouter
import com.neop2p.data.p2p.store.PeerRegistry
import com.neop2p.data.reputation.ReputationSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level coordinator for the P2P message-routing pipeline.
 *
 * Owns the lifecycle of the transport, identity, Nostr, reputation, and router
 * components, and dispatches inbound [AppMessage]s to the correct handler.
 * [start] is idempotent; [stop] tears down the network-facing components.
 */
@Singleton
class P2POrchestrator @Inject constructor(
    private val identityManager: IdentityManager,
    private val p2pTransport: HybridP2PTransport,
    private val signal: SignalProtocol,
    private val nostrClient: NostrClient,
    private val reputation: ReputationSystem,
    private val peerRegistry: PeerRegistry,
    private val queue: OfflineQueue,
    private val chatRouter: ChatRouter,
    private val offerRouter: OfferRouter,
    private val escrowService: EscrowService,
    private val scope: CoroutineScope
) {
    @Volatile private var running = false
    @Volatile private var inboundJob: Job? = null

    suspend fun start(): Result<Unit> {
        if (running) return Result.success(Unit)
        running = true
        return try {
            // Non-fatal: log and continue if Signal init fails so the rest of the
            // pipeline (transport, identity, Nostr) can still come up.
            signal.initialize().onFailure {
                Log.w(TAG, "Signal init failed (continuing): ${it.message}")
            }
            p2pTransport.start()
            val identity = identityManager.getOrCreateIdentity()
            nostrClient.connect(identity.nostrPubkeyHex)
            reputation.initialize()
            offerRouter.startListening(scope)
            listenInbound()
            launchPeerDrain()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Orchestrator start failed", e)
            running = false
            Result.failure(e)
        }
    }

    private fun listenInbound() {
        inboundJob?.cancel()
        inboundJob = scope.launch {
            p2pTransport.incomingMessages.collect { env ->
                val msg = EnvelopeCodec.decode(env) ?: return@collect
                when (msg) {
                    // msg.from is the peer requesting our bundle; reply to them.
                    is AppMessage.PreKeyRequest -> {
                        signal.sendPreKeyBundle(msg.from)
                            .onSuccess { bundle -> queue.send(msg.from, bundle) }
                    }
                    is AppMessage.PreKeyBundle -> {
                        val bundle = signal.deserializeBundle(msg.bundle)
                        signal.createSession(msg.from, bundle)
                    }
                    is AppMessage.Chat -> chatRouter.receiveChat(msg)
                    is AppMessage.Offer -> offerRouter.receiveOffer(msg)
                    is AppMessage.EscrowEvent -> {
                        // Minimal stub: acknowledge receipt. The dedicated escrow-event
                        // message contract is not yet defined, so we only log. Do NOT
                        // invent EscrowService transition signatures.
                        Log.d(TAG, "EscrowEvent received escrow=${msg.escrowId} event=${msg.event} from=${msg.from}")
                    }
                }
            }
        }
    }

    /**
     * Drains the offline queue for a peer once it reports online. Returns whether
     * every pending message was successfully sent over the transport.
     */
    private suspend fun drainPending(peerId: String) {
        if (!running) return
        queue.drainFor(peerId) { msg ->
            if (!running) return@drainFor false
            val env = EnvelopeCodec.encode(msg)
            p2pTransport.send(peerId, env.data, env.type).isSuccess
        }
    }

    private fun launchPeerDrain() {
        scope.launch {
            peerRegistry.peers
                .collectLatest { peers ->
                    for ((peerId, info) in peers) {
                        if (info.isOnline) drainPending(peerId)
                    }
                }
        }
    }

    suspend fun stop() {
        if (!running) return
        running = false
        inboundJob?.cancel()
        inboundJob = null
        nostrClient.disconnect()
        p2pTransport.stop()
    }

    companion object {
        private const val TAG = "P2POrchestrator"
    }
}
