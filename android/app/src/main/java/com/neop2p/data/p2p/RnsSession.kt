package com.neop2p.data.p2p

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.lxmf.DeliveryMethod
import network.reticulum.lxmf.LXMFConstants
import network.reticulum.lxmf.LXMessage
import network.reticulum.lxmf.LXMRouter
import network.reticulum.transport.AnnounceHandler
import network.reticulum.transport.Transport
import org.msgpack.core.MessagePack
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure-JVM RNS + LXMF session core (no Android dependencies — unit-testable).
 *
 * Wraps a client-only [Reticulum] instance (`enableTransport=false` on phones)
 * plus an [LXMRouter] for chat/escrow/arbitration messaging. Peer identity
 * mapping:
 *
 * - Our LXMF delivery destination is announced with the app's libp2p [myPeerId]
 *   as the announce displayName (msgpack `[displayName, stampCost]`).
 * - A peer's announce is parsed by [handlePeerAnnounce] into a
 *   `peerId (libp2p) <-> LXMF destination hash` map, so the app's existing
 *   peerId-addressed [send] works unchanged.
 * - Inbound LXMF messages are mapped back to the sender's peerId via the
 *   source destination hash and emitted as [Inbound] (type = LXMF title,
 *   data = FIELD_CUSTOM_DATA bytes — the app's EnvelopeCodec envelope).
 *
 * Delivery is DIRECT (link-based, forward secrecy) with LXMF's built-in
 * retries (5 attempts, 10s); messages >319B auto-Resource over the link.
 * Files travel as FIELD_FILE_ATTACHMENTS and surface on [receivedFiles].
 */
class RnsSession(
    val configDir: String,
    seed: ByteArray,
    val myPeerId: String,
) {
    /** An inbound app-level message: [type] = LXMF title, [data] = envelope bytes. */
    data class Inbound(
        val type: String,
        val fromPeerId: String,
        val data: ByteArray,
    )

    /** An inbound file transfer (payment proof / screenshot). */
    data class ReceivedFile(
        val fromPeerId: String,
        val fileName: String,
        val data: ByteArray,
    )

    private val identity = Identity.fromPrivateKey(seed)
    private var router: LXMRouter? = null
    private var deliveryDest: Destination? = null

    /** peerId (libp2p) -> LXMF delivery destination hash (hex). */
    private val destHashByPeerId = ConcurrentHashMap<String, String>()
    /** LXMF delivery destination hash (hex) -> peerId (libp2p). */
    private val peerIdByDestHash = ConcurrentHashMap<String, String>()
    /** peerId -> last announce timestamp (ms). */
    private val lastSeenByPeerId = ConcurrentHashMap<String, Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _incoming = MutableSharedFlow<Inbound>(replay = 0, extraBufferCapacity = 64)
    val incoming: SharedFlow<Inbound> = _incoming.asSharedFlow()

    private val _receivedFiles = MutableSharedFlow<ReceivedFile>(replay = 0, extraBufferCapacity = 16)
    val receivedFiles: SharedFlow<ReceivedFile> = _receivedFiles.asSharedFlow()

    /** Emits a peerId every time a peer announces (fresh path + identity). */
    private val _peerSeen = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val peerSeen: SharedFlow<String> = _peerSeen.asSharedFlow()

    fun start(): Result<Unit> = runCatching {
        if (router != null) return@runCatching
        // Reticulum is a JVM-wide singleton: the first session starts it, any
        // later session reuses the running instance (transport mode is disabled
        // on phones, so the transport identity is cosmetic for routing).
        val alreadyRunning = try {
            Reticulum.getInstance()
            true
        } catch (_: IllegalStateException) {
            false
        }
        if (!alreadyRunning) {
            Reticulum.start(
                configDir = configDir,
                enableTransport = false,
                transportIdentity = identity,
            )
        }
        activeSessions.incrementAndGet()
        val lxmf = LXMRouter(identity = identity, storagePath = configDir)
        router = lxmf
        // displayName = our libp2p peerId so peers can map announce -> peerId.
        deliveryDest = lxmf.registerDeliveryIdentity(identity, myPeerId)
        lxmf.registerDeliveryCallback { handleInbound(it) }
        lxmf.registerFailedDeliveryCallback { msg ->
            println("[RnsSession] LXMF delivery failed for ${msg.destinationHash.toHexString()} (${msg.title})")
        }
        lxmf.start()
        lxmf.announce(deliveryDest!!)
        // Parse peer announces: displayName (peerId) + stamp cost.
        Transport.registerAnnounceHandler(
            handler = AnnounceHandler { destHash, _, appData ->
                handlePeerAnnounce(destHash, appData)
                false
            },
            aspectFilter = "lxmf.delivery",
        )
        // Periodic re-announce keeps our path + peerId fresh (RNS announce
        // cache is ephemeral; peers that joined before our first announce
        // learn us on the next one).
        scope.launch {
            while (isActive) {
                delay(RE_ANNOUNCE_INTERVAL_MS)
                runCatching { lxmf.announce(deliveryDest!!) }
            }
        }
        println("[RnsSession] started (identity ${identity.hexHash.take(12)}…, dest ${deliveryDest!!.hexHash.take(12)}…)")
    }

    fun stop() {
        scope.cancel()
        router?.stop()
        router = null
        deliveryDest = null
        // Only stop the shared Reticulum if no other session is using it.
        // (In the app there is exactly one session; in tests the last session
        // to stop tears the singleton down.)
        if (activeSessions.decrementAndGet() <= 0) {
            Reticulum.stop()
        }
    }

    /**
     * Send an app-level envelope to [toPeerId] over LXMF (DIRECT link).
     * Fails fast if the peer has never announced (no path/identity known) —
     * the caller's offline queue keeps the message and retries on the next
     * announce.
     */
    fun send(toPeerId: String, data: ByteArray, type: String): Result<Unit> = runCatching {
        val lxmf = router ?: throw IllegalStateException("RNS not started")
        val destHex = destHashByPeerId[toPeerId]
            ?: throw IllegalStateException("No RNS path to $toPeerId (peer has not announced)")
        val destHash = hexToBytes(destHex)
        val peerIdentity = Identity.recall(destHash)
            ?: throw IllegalStateException("Unknown RNS identity for $toPeerId")
        val dest = Destination.create(
            identity = peerIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery",
        )
        val source = deliveryDest ?: throw IllegalStateException("RNS delivery destination not registered")
        val msg = LXMessage.create(
            destination = dest,
            source = source,
            content = "",
            title = type,
            fields = mutableMapOf(LXMFConstants.FIELD_CUSTOM_DATA to data),
            desiredMethod = DeliveryMethod.DIRECT,
        )
        runBlocking { lxmf.handleOutbound(msg) }
    }

    /**
     * Send a file (payment proof / screenshot) as an LXMF attachment.
     * LXMF auto-Resources messages >319B (chunked + BZ2 + retransmission),
     * replacing the WebRTC data channel for Phase 2.
     */
    fun sendFile(toPeerId: String, fileName: String, data: ByteArray): Result<Unit> = runCatching {
        val lxmf = router ?: throw IllegalStateException("RNS not started")
        val destHex = destHashByPeerId[toPeerId]
            ?: throw IllegalStateException("No RNS path to $toPeerId (peer has not announced)")
        val destHash = hexToBytes(destHex)
        val peerIdentity = Identity.recall(destHash)
            ?: throw IllegalStateException("Unknown RNS identity for $toPeerId")
        val dest = Destination.create(
            identity = peerIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery",
        )
        val source = deliveryDest ?: throw IllegalStateException("RNS delivery destination not registered")
        val msg = LXMessage.create(
            destination = dest,
            source = source,
            content = "",
            title = "file",
            fields = mutableMapOf(
                LXMFConstants.FIELD_FILE_ATTACHMENTS to
                    listOf(listOf(fileName.toByteArray(Charsets.UTF_8), data)),
            ),
            desiredMethod = DeliveryMethod.DIRECT,
        )
        runBlocking { lxmf.handleOutbound(msg) }
    }

    /** Whether an active DIRECT LXMF link exists to [peerId]. */
    fun isDirect(peerId: String): Boolean {
        val destHex = destHashByPeerId[peerId] ?: return false
        return router?.hasActiveLink(destHex) ?: false
    }

    /** All peers that have announced at least once this session. */
    fun knownPeers(): List<String> = destHashByPeerId.keys.toList()

    /** Last announce timestamp (ms) for [peerId], 0 if never announced. */
    fun lastSeen(peerId: String): Long = lastSeenByPeerId[peerId] ?: 0L

    /** Re-announce our delivery destination (fresh path + peerId for peers). */
    fun reannounce() {
        val lxmf = router ?: return
        val dest = deliveryDest ?: return
        runCatching { lxmf.announce(dest) }
    }

    /** The LXMF delivery destination hash (hex) of our own delivery identity. */
    fun myDestHashHex(): String = deliveryDest?.hexHash ?: ""

    /** The LXMF delivery destination hash (hex) of a known peer, or null. */
    internal fun destHashOf(peerId: String): String? = destHashByPeerId[peerId]

    /** The peerId (libp2p) that announced the given LXMF dest hash, or null. */
    internal fun peerIdOfDestHash(destHashHex: String): String? = peerIdByDestHash[destHashHex]

    /** The LXMF delivery destination hash (hex) of our own delivery identity. */
    internal fun myDestHash(): ByteArray? = deliveryDest?.hash

    /** Our registered LXMF delivery destination (test accessor). */
    internal fun deliveryDestination(): Destination? = deliveryDest

    /**
     * Parse a peer's LXMF delivery announce appData (msgpack
     * `[displayName, stampCost]`) and record the peerId <-> dest hash mapping.
     * Also invoked directly by tests (Transport skips announces for local
     * destinations, so the in-JVM loopback test feeds it by hand).
     */
    internal fun handlePeerAnnounce(destHash: ByteArray, appData: ByteArray?) {
        if (appData == null || appData.isEmpty()) return
        try {
            val unpacker = MessagePack.newDefaultUnpacker(appData)
            val size = unpacker.unpackArrayHeader()
            if (size >= 1 && !unpacker.tryUnpackNil()) {
                val nameLen = unpacker.unpackBinaryHeader()
                val nameBytes = ByteArray(nameLen)
                unpacker.readPayload(nameBytes)
                val peerId = String(nameBytes, Charsets.UTF_8)
                if (peerId.isNotBlank()) {
                    val destHex = destHash.toHexString()
                    destHashByPeerId[peerId] = destHex
                    peerIdByDestHash[destHex] = peerId
                    lastSeenByPeerId[peerId] = System.currentTimeMillis()
                    _peerSeen.tryEmit(peerId)
                }
            }
        } catch (e: Exception) {
            println("[RnsSession] Failed to parse announce appData: ${e.message}")
        }
    }

    private fun handleInbound(msg: LXMessage) {
        val sourceHex = msg.sourceHash.toHexString()
        val peerId = peerIdByDestHash[sourceHex]
        if (peerId == null) {
            println("[RnsSession] Inbound LXMF from unknown peer $sourceHex — dropping")
            return
        }
        lastSeenByPeerId[peerId] = System.currentTimeMillis()

        // File attachments (payment proofs / screenshots).
        val attachments = msg.fields[LXMFConstants.FIELD_FILE_ATTACHMENTS]
        if (attachments is List<*>) {
            for (item in attachments) {
                if (item is List<*> && item.size >= 2) {
                    val name = (item[0] as? ByteArray)?.toString(Charsets.UTF_8) ?: continue
                    val fileData = item[1] as? ByteArray ?: continue
                    _receivedFiles.tryEmit(ReceivedFile(peerId, name, fileData))
                    _incoming.tryEmit(Inbound("file", peerId, fileData))
                }
            }
            return
        }

        // Regular envelope: title = app message type, FIELD_CUSTOM_DATA = the
        // app's EnvelopeCodec bytes (binary-safe; content is UTF-8 String).
        val data = msg.fields[LXMFConstants.FIELD_CUSTOM_DATA] as? ByteArray
            ?: msg.content.toByteArray(Charsets.UTF_8)
        _incoming.tryEmit(Inbound(msg.title, peerId, data))
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    companion object {
        private const val RE_ANNOUNCE_INTERVAL_MS = 5 * 60 * 1000L

        /** Number of live RnsSession instances sharing the Reticulum singleton. */
        private val activeSessions = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
