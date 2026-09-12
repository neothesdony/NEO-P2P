package com.neop2p.data.p2p

import android.content.Context
import android.util.Log
import com.neop2p.NeoP2PConfig
import com.neop2p.data.local.TransportNodeStore
import com.neop2p.data.p2p.P2PTransport.TransportMessage
import com.neop2p.data.p2p.P2PTransport.TransportState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RNS + LXMF transport (Phase 2: chat over LXMF, dual-run with the legacy
 * libp2p/Nostr/WebRTC stack).
 *
 * Wraps [RnsSession] — a client-only Reticulum instance (`enableTransport=false`
 * on phones) with a deterministic 64-byte identity derived from the BIP-39
 * mnemonic (SLIP-10 m/44'/999'/0'/0/1 curve25519 + m/44'/999'/0'/0/2 ed25519),
 * so peer IDs stay stable across the libp2p→RNS migration.
 *
 * Peer addressing: the app's peerId (libp2p base58) rides as the LXMF announce
 * displayName, so [send] keeps the existing peerId-addressed API. Inbound
 * LXMF messages are mapped back to the sender's peerId and emitted as
 * [TransportMessage]s (type = LXMF title, data = the app's EnvelopeCodec
 * envelope bytes) — the orchestrator's existing [AppMessage] dispatch works
 * unchanged. Files arrive via LXMF FIELD_FILE_ATTACHMENTS and surface on
 * [receivedFiles] (replaces the WebRTC data channel).
 *
 * Phase 3 adds the announce-based offer feed (publish/subscribe); until then
 * publish/subscribe are stubs and the legacy stack remains the active
 * transport (dual-run).
 */
@Singleton
class RnsTransport @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val identityManager: IdentityManager,
    private val transportNodeStore: TransportNodeStore
) : P2PTransport {

    private var session: RnsSession? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    override val state = MutableStateFlow(TransportState())
    override val incomingMessages = MutableSharedFlow<TransportMessage>(replay = 0, extraBufferCapacity = 64)

    /** Inbound file transfers (payment proofs / screenshots) over LXMF. */
    val receivedFiles = MutableSharedFlow<RnsSession.ReceivedFile>(replay = 0, extraBufferCapacity = 16)

    /** Emits a peerId every time a peer announces over RNS (fresh path + identity). */
    val peerSeen = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)

    /** Emits an offer-feed announce (digest JSON) from a peer (Phase 3). */
    val offerAnnounces = MutableSharedFlow<RnsSession.OfferAnnounce>(replay = 0, extraBufferCapacity = 64)

    override suspend fun start(): Result<Unit> = runCatching {
        if (session != null) return@runCatching
        val identity = identityManager.getOrCreateIdentity()
        // Tier 1: AutoInterface for LAN peer discovery — held while the
        // session lives. On stock Android, multicast reception on Wi-Fi is
        // disabled by default; without the lock AutoInterface's discovery
        // sockets join the group but never receive (observed pattern from
        // Sideband/rns-android: acquire for the service lifetime).
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val lock = wifi?.createMulticastLock("neop2p-ai")
        lock?.setReferenceCounted(false)
        lock?.acquire()
        multicastLock = lock
        val rns = RnsSession(
            configDir = context.filesDir.resolve("reticulum").absolutePath,
            seed = KeyDerivation.rnsIdentity(identityManager.getMasterSeed()),
            myPeerId = identity.peerId,
            // Default node first (always connected), then user-added extras.
            transportNodes = currentTransportNodes(),
            // Community presets participate in failover only (backup ferries
            // while the primary is offline) — never connected by default.
            communityNodes = com.neop2p.data.local.TransportNodeStore.communityPresets().map { it.host to it.port },
            enableAutoInterface = true,
            // F1: sign the neop2p.identity binding announce so peers can verify
            // that this peerId really owns this RNS identity.
            libp2pPrivKey = identityManager.getLibp2pPrivateKey(),
        )
        rns.start().getOrThrow()
        session = rns
        // Forward inbound envelopes + files into the P2PTransport flows.
        scope.launch {
            rns.incoming.collect { inbound ->
                incomingMessages.emit(
                    TransportMessage(
                        type = inbound.type,
                        fromPeerId = inbound.fromPeerId,
                        toPeerId = identity.peerId,
                        data = inbound.data,
                        authenticated = true,
                        senderDestHash = inbound.senderDestHash
                    )
                )
            }
        }
        scope.launch {
            rns.receivedFiles.collect { file ->
                receivedFiles.emit(file)
            }
        }
        scope.launch {
            rns.peerSeen.collect { peerId ->
                peerSeen.emit(peerId)
            }
        }
        scope.launch {
            rns.offerAnnounces.collect { announce ->
                offerAnnounces.emit(announce)
            }
        }
        Log.i(TAG, "started (identity ${identity.peerId.take(12)}…, dest ${rns.myDestHashHex().take(12)}…)")
        state.value = TransportState(isRunning = true, transportType = "rns")
    }.onFailure { Log.e(TAG, "start failed: ${it.message}") }

    override suspend fun stop(): Result<Unit> = runCatching {
        session?.stop()
        session = null
        multicastLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        multicastLock = null
        state.value = TransportState()
    }.onFailure { Log.e(TAG, "stop failed: ${it.message}") }

    /** Forward the app's foreground/background state into the session's
     *  paced offer loop (idle = slower tick, battery). Null-safe: the
     *  sweep loop re-applies it every tick, so a pre-start call is fine. */
    fun setIdleMode(idle: Boolean) {
        session?.idleMode = idle
    }

    override suspend fun send(toPeerId: String, data: ByteArray, type: String): Result<Unit> {
        val rns = session ?: return Result.failure(IllegalStateException("RNS not started"))
        return rns.send(toPeerId, data, type)
    }

    /** Send a file over LXMF (auto-Resource for >319B). */
    suspend fun sendFile(toPeerId: String, fileName: String, data: ByteArray): Result<Unit> {
        val rns = session ?: return Result.failure(IllegalStateException("RNS not started"))
        return rns.sendFile(toPeerId, fileName, data)
    }

    override suspend fun publish(topic: String, data: ByteArray): Result<Unit> =
        Result.failure(NotImplementedError("Phase 3 — announce appData"))

    override suspend fun subscribe(topic: String): Result<Unit> = Result.success(Unit)

    /** Register a digest for the paced offer re-announce loop (edit-safe). */
    fun trackOfferDigest(digestJson: String) {
        session?.trackOfferDigest(digestJson)
    }

    /** Stop re-announcing an offer (deleted / MATCHED / terminal status). */
    fun untrackOfferDigest(offerId: String) {
        session?.untrackOfferDigest(offerId)
    }

    /**
     * Seed the paced re-announce loop from the durable offer table
     * (cold-start re-hydration).
     */
    fun setOpenOfferDigests(digests: Map<String, String>) {
        session?.setOpenOfferDigests(digests)
    }

    /**
     * Seed the paced terminal-tombstone set (2026-09-02, 3rd-device
     * convergence): COMPLETED/CANCELLED offers keep re-announcing a
     * tombstone digest so non-participant peers converge on the terminal
     * status. Keyed by offer id → tombstone digest JSON.
     */
    fun setTerminalTombstones(tombstones: Map<String, String>) {
        session?.setTerminalTombstones(tombstones)
    }

    /**
     * Pull-to-refresh: re-announce all tracked offer digests + the delivery
     * destination immediately (rate-capped), so peers re-fetch our offers
     * without waiting for the paced loop's next tick.
     */
    fun refreshFeed() {
        session?.refreshFeed()
    }

    /** Request the full offer JSON from [toPeerId] over LXMF. */
    suspend fun sendOfferRequest(toPeerId: String, offerId: String): Result<Unit> {
        val rns = session ?: return Result.failure(IllegalStateException("RNS not started"))
        return rns.sendOfferRequest(toPeerId, offerId)
    }

    /** Send the full offer JSON to [toPeerId] over LXMF. */
    suspend fun sendOffer(toPeerId: String, offerJson: String): Result<Unit> {
        val rns = session ?: return Result.failure(IllegalStateException("RNS not started"))
        return rns.sendOffer(toPeerId, offerJson)
    }

    /** Send an offer status update (MATCHED/ESCROWED/PAUSED/OPEN) over LXMF. */
    suspend fun sendOfferStatus(
        toPeerId: String,
        offerId: String,
        status: String,
        matchedPeerId: String? = null,
        buyerBtcAddress: String? = null,
        buyerPubKeyHex: String? = null,
        buyerAddressAttestation: String? = null,
        authorPeerId: String? = null,
    ): Result<Unit> {
        val rns = session ?: return Result.failure(IllegalStateException("RNS not started"))
        return rns.sendOfferStatus(toPeerId, offerId, status, matchedPeerId, buyerBtcAddress, buyerPubKeyHex, buyerAddressAttestation, authorPeerId)
    }

    /** Tell [toPeerId] that an offer was deleted (tombstone propagation). */
    suspend fun sendOfferDelete(toPeerId: String, offerId: String): Result<Unit> {
        val rns = session ?: return Result.failure(IllegalStateException("RNS not started"))
        return rns.sendOfferDelete(toPeerId, offerId)
    }

    /** Send a signed attestation to [toPeerId] over LXMF. */
    suspend fun sendAttestation(toPeerId: String, json: String): Result<Unit> {
        val rns = session ?: return Result.failure(IllegalStateException("RNS not started"))
        return rns.sendAttestation(toPeerId, json)
    }

    /** Send an escrow status sync over LXMF. */
    suspend fun sendEscrowStatus(
        toPeerId: String,
        escrowId: String,
        status: String,
        fields: Map<String, String>,
    ): Result<Unit> {
        val rns = session ?: return Result.failure(IllegalStateException("RNS not started"))
        return rns.sendEscrowStatus(toPeerId, escrowId, status, fields)
    }

    /** Send a dispute-opened event over LXMF. */
    suspend fun sendDispute(
        toPeerId: String,
        escrowId: String,
        openedBy: String,
        reason: String,
        fields: Map<String, String>,
    ): Result<Unit> {
        val rns = session ?: return Result.failure(IllegalStateException("RNS not started"))
        return rns.sendDispute(toPeerId, escrowId, openedBy, reason, fields)
    }

    /** Send dispute evidence (image attachment) over LXMF. */
    suspend fun sendEvidence(
        toPeerId: String,
        escrowId: String,
        submitter: String,
        description: String,
        mimeType: String,
        imageBytes: ByteArray,
    ): Result<Unit> {
        val rns = session ?: return Result.failure(IllegalStateException("RNS not started"))
        return rns.sendEvidence(toPeerId, escrowId, submitter, description, mimeType, imageBytes)
    }

    /** Send an arbitration resolution over LXMF. */
    suspend fun sendResolution(
        toPeerId: String,
        escrowId: String,
        decision: String,
        arbitratorSigHex: String,
        notes: String?,
        sellerRefundAddress: String?,
        signedTxHex: String?,
    ): Result<Unit> {
        val rns = session ?: return Result.failure(IllegalStateException("RNS not started"))
        return rns.sendResolution(toPeerId, escrowId, decision, arbitratorSigHex, notes, sellerRefundAddress, signedTxHex)
    }

    override fun isDirect(): Boolean = session?.let { rns ->
        rns.knownPeers().any { rns.isDirect(it) }
    } ?: false

    /** Peers that have announced over RNS this session (peerId list). */
    fun knownPeers(): List<String> = session?.knownPeers() ?: emptyList()

    /** Re-announce our delivery destination (fresh path + peerId for peers). */
    fun reannounce() {
        session?.reannounce()
    }

    /** True if an active DIRECT LXMF link exists to [peerId]. */
    fun isDirectTo(peerId: String): Boolean = session?.isDirect(peerId) ?: false

    /** F1: true when this peerId's claim is backed by a verified binding from that dest. */
    fun isVerifiedSender(peerId: String, senderDestHash: String): Boolean =
        session?.isVerifiedSender(peerId, senderDestHash) ?: false

    /** The verified destination for [peerId] when a binding has been learned. */
    fun verifiedDestFor(peerId: String): String? = session?.verifiedDestFor(peerId)

    /** The full transport-node list (default + user-added extras). */
    private fun currentTransportNodes(): List<Pair<String, Int>> = buildList {
        add(NeoP2PConfig.RNS_TRANSPORT_NODE_HOST to NeoP2PConfig.RNS_TRANSPORT_NODE_PORT)
        transportNodeStore.all().forEach { add(it.host to it.port) }
    }

    /** Live-apply the transport-node set from the store (Tier 3, no restart). */
    suspend fun applyTransportNodes() {
        session?.applyTransportNodes(currentTransportNodes())
    }

    companion object {
        private const val TAG = "RnsTransport"
    }
}
