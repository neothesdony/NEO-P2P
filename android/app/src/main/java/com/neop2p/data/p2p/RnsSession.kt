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
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.auto.AutoInterface
import network.reticulum.interfaces.toRef
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
    /** RNS transport nodes (TCP servers) to connect to — the VPS transport
     *  node in production (first entry), plus optional extra nodes (Tier 3).
     *  When empty, no network interface is registered (loopback-only, used by
     *  tests). Each node is an independent TCP client interface; per-endpoint
     *  reconnect (5s) keeps dead nodes self-healing without disturbing the
     *  live ones, and one mesh spans every node. */
    private val transportNodes: List<Pair<String, Int>> = emptyList(),
    /** Test seam: paced offer re-announce tick. Overridden by in-JVM tests so
     *  pacing is verifiable without waiting the production 10s. */
    internal val offerReannounceIntervalMs: Long = OFFER_REANNOUNCE_INTERVAL_MS,
    /** Test seam: idle-paced offer re-announce tick, used when [idleMode]
     *  is true (app backgrounded — see P2POrchestrator). Overridden by
     *  in-JVM tests so idle pacing is verifiable without production waits. */
    internal val idleReannounceIntervalMs: Long = OFFER_REANNOUNCE_IDLE_INTERVAL_MS,
    /** True while the app is backgrounded: the paced offer loop stretches
     *  from [offerReannounceIntervalMs] to [idleReannounceIntervalMs] to
     *  reduce idle battery drain. The 20s delivery announce is NEVER
     *  stretched — it is the NAT keepalive. Set/cleared by the
     *  orchestrator from AppForegroundTracker. */
    internal var idleMode: Boolean = false,
    /** Enable local (LAN) peer discovery via RNS AutoInterface (IPv6
     *  link-local multicast + per-peer UDP unicast). Default OFF so JVM
     *  tests never touch real network sockets; [RnsTransport] (Android)
     *  turns it on — two phones on one Wi-Fi then exchange announces, paths,
     *  and DIRECT LXMF links with NO transport node in between (Tier 1). */
    private val enableAutoInterface: Boolean = false,
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

    /** An inbound offer-feed announce: [digestJson] is the compact offer digest. */
    data class OfferAnnounce(
        val fromPeerId: String,
        val digestJson: String,
    )

    private val identity = Identity.fromPrivateKey(seed)
    private var router: LXMRouter? = null
    private var deliveryDest: Destination? = null
    private var offersDest: Destination? = null
    /** Active TCP client interfaces, keyed by "host:port". */
    private val tcpInterfaces = ConcurrentHashMap<String, TCPClientInterface>()
    /** Active AutoInterface for LAN peer discovery, when enabled. */
    private var autoInterface: AutoInterface? = null

    /** peerId (libp2p) -> LXMF delivery destination hash (hex). */
    private val destHashByPeerId = ConcurrentHashMap<String, String>()
    /** LXMF delivery destination hash (hex) -> peerId (libp2p). */
    private val peerIdByDestHash = ConcurrentHashMap<String, String>()
    /** announced RNS identity hash (hex) -> peerId (libp2p). Seeds the offer
     *  feed cross-check so a digest that beats its delivery announce is not
     *  dropped as "unknown identity" (found by RnsLoadTest 2026-09-01). */
    private val peerIdByIdentityHash = ConcurrentHashMap<String, String>()
    /**
     * Offer digests that arrived before the peer's delivery announce (and so
     * before the identity-hash -> peerId mapping existed), keyed by identity
     * hash. Flushed (emitted) when the delivery announce arrives.
     *
     * BOUNDED (Bug A2, 2026-09-01): a hostile peer that announces offers under
     * an identity which never delivers a delivery-announce must not grow this
     * map without limit. Per identity ≤ [MAX_PENDING_OFFERS_PER_IDENTITY]
     * digests (deduped); at most [MAX_PENDING_OFFER_IDENTITIES] identities,
     * oldest evicted first. Overflows drop the new digest + warn.
     */
    private val pendingOfferAnnouncesByIdentityHash =
        java.util.concurrent.ConcurrentHashMap<String, PendingOfferAnnounces>()
    /** peerId -> last announce timestamp (ms). */
    private val lastSeenByPeerId = ConcurrentHashMap<String, Long>()

    /**
     * Bounded per-identity deferral buffer: a LinkedHashSet (insertion-ordered
     * dedup) of digests plus the first-deferral time for LRU eviction.
     */
    private class PendingOfferAnnounces(
        val identityHashHex: String,
        val firstSeenMs: Long,
    ) {
        val digests = java.util.LinkedHashSet<String>()
    }

    /**
     * Live offers to re-announce on the RNS feed, keyed by offer id so a
     * caller can update a digest in place (edit). The paced loop cycles this
     * set at [OFFER_REANNOUNCE_INTERVAL_MS], one digest per announce — the
     * one-shot offer announce (publishOffer at create/edit) alone left peers
     * that joined later without discovery, and a cold-started seller with
     * open offers re-announced nothing. 2026-09-02: locked offers
     * (MATCHED/ESCROWED) stay in the set — the digest embeds the status, so a
     * status change changes the hash and non-participant peers re-fetch and
     * converge on "taken".
     */
    private val offerDigestsById = ConcurrentHashMap<String, String>()

    /**
     * Terminal-status tombstones (COMPLETED/CANCELLED) to re-announce on the
     * RNS feed, keyed by offer id. 2026-09-02 (3rd-device convergence): a
     * terminal offer leaves the live digest set, so non-participant peers
     * would otherwise never learn the status change and keep the stale OPEN
     * row forever. The paced loop re-announces each tombstone once per full
     * cycle of the live set (or once per sweep tick when the live set is
     * empty) — a digest-only `{v,id,t}` that carries no status.
     */
    private val terminalTombstonesById = ConcurrentHashMap<String, String>()

    /** Round-robin cursor for the paced offer re-announce loop. */
    private var offerReannounceCursor = 0

    /**
     * Tick counter for the paced tombstone re-announce cadence (2026-09-02).
     * Tombstones are announced once per [TOMBSTONE_REANNOUNCE_TICKS] live
     * ticks so the combined live+tombstone rate stays under the fork's
     * per-destination cap — the old cursor-mod-live-size logic announced a
     * tombstone EVERY tick when the live set had one offer, doubling the
     * rate to 24/30s and the node blocked the whole dest ("Blocking
     * rebroadcast ... due to excessive announce rate").
     */
    private var tombstoneReannounceCursor = 0

    /**
     * Test seam: count of offer digests re-announced by the paced loop (not
     * the one-shot publishOffer). Lets in-JVM tests verify pacing without a
     * live interface.
     */
    @Volatile internal var pacedOfferReannounces = 0L
        private set

    /**
     * Test seam: count of tombstone digests re-announced by the paced loop
     * (2026-09-02). Lets in-JVM tests verify tombstone pacing without a live
     * interface.
     */
    @Volatile internal var pacedTombstoneReannounces = 0L
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Failed DIRECT signaling messages awaiting a fresh path (S05/S06).
     *
     * When a DIRECT link dies mid-conversation (interface flap, NAT drop),
     * LXMF's receipt times out and the message is FAILED — the app's send()
     * already returned success, so without this the message is silently
     * lost. Chat/pre-key already ride the durable OfflineQueue (their rows
     * stay until a drain succeeds), so only signaling types are re-queued
     * here. Re-send is triggered by the next peer announce (fresh path),
     * bounded per message, capped in size.
     */
    private class PendingResend(
        val toPeerId: String,
        val type: String,
        val data: ByteArray,
        var attempts: Int = 0,
    )

    private val pendingResends = java.util.concurrent.ConcurrentHashMap<String, PendingResend>()

    private val _incoming = MutableSharedFlow<Inbound>(replay = 0, extraBufferCapacity = 64)
    val incoming: SharedFlow<Inbound> = _incoming.asSharedFlow()

    private val _receivedFiles = MutableSharedFlow<ReceivedFile>(replay = 0, extraBufferCapacity = 16)
    val receivedFiles: SharedFlow<ReceivedFile> = _receivedFiles.asSharedFlow()

    /** Emits a peerId every time a peer announces (fresh path + identity). */
    private val _peerSeen = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val peerSeen: SharedFlow<String> = _peerSeen.asSharedFlow()

    /** Emits an offer-feed announce (digest JSON) from a peer. */
    private val _offerAnnounces = MutableSharedFlow<OfferAnnounce>(replay = 0, extraBufferCapacity = 64)
    val offerAnnounces: SharedFlow<OfferAnnounce> = _offerAnnounces.asSharedFlow()

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
        // Phase 4: connect to the RNS transport node(s) (TCP client
        // interfaces). Phones are client-only (enableTransport=false); the
        // nodes route announces/paths between peers and to the LXMF
        // propagation node. The interfaces are registered BEFORE the LXMF
        // router starts so the first announce has a live interface to
        // broadcast on. Every node is a packet ferry, not a trust anchor:
        // traffic stays end-to-end encrypted and announces are signed, so
        // more nodes = more reach, never less security.
        connectTransportNodes(transportNodes)
        // Tier 1: local (LAN) peer discovery. AutoInterface uses IPv6
        // link-local multicast (discovery port 29716) + per-peer UDP unicast
        // (data port 42671); discovered peers spawn AutoInterfacePeer
        // interfaces that self-register with Transport. Two phones on one
        // Wi-Fi then exchange announces/paths/DIRECT LXMF links with no
        // transport node in between — the same peerId-addressed send() API
        // works unchanged because every medium feeds one RNS mesh.
        // Android note: the app holds a WifiManager MulticastLock (see
        // RnsTransport) so the multicast discovery sockets actually receive.
        if (enableAutoInterface) {
            val auto = AutoInterface(name = "AutoInterface")
            auto.onPacketReceived = { data, receivedIface ->
                Transport.inbound(data, (receivedIface ?: auto).toRef())
            }
            Transport.registerInterface(auto.toRef())
            auto.start()
            autoInterface = auto
            println("[RnsSession] AutoInterface enabled (LAN peer discovery)")
        }
        val lxmf = LXMRouter(identity = identity, storagePath = configDir)
        router = lxmf
        // displayName = our libp2p peerId so peers can map announce -> peerId.
        deliveryDest = lxmf.registerDeliveryIdentity(identity, myPeerId)
        lxmf.registerDeliveryCallback { handleInbound(it) }
        lxmf.registerFailedDeliveryCallback { msg ->
            println("[RnsSession] LXMF delivery failed for ${msg.destinationHash.toHexString()} (${msg.title})")
            // S05/S06: a DIRECT link that died mid-conversation fails the
            // receipt AFTER send() already returned success. Re-queue the
            // signaling payload so the next peer announce (fresh path) resends
            // it. Chat/pre-key are excluded — they ride the durable
            // OfflineQueue and would double-send.
            val data = msg.fields[LXMFConstants.FIELD_CUSTOM_DATA] as? ByteArray ?: return@registerFailedDeliveryCallback
            val peerId = peerIdByDestHash[msg.destinationHash.toHexString()] ?: return@registerFailedDeliveryCallback
            queueResend(peerId, msg.title, data)
        }
        lxmf.start()
        lxmf.announce(deliveryDest!!)
        // Phase 3: the offer-feed destination (neop2p/offers). Announced with
        // a compact offer digest as appData (RNS announce appData is capped at
        // ~300 bytes — the full offer JSON travels over LXMF on request).
        offersDest = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "neop2p",
            "offers",
        )
        Transport.registerDestination(offersDest!!)
        // Parse peer announces: displayName (peerId) + stamp cost. The fork
        // (rns-core Transport.processAnnounce) already remembers every valid
        // announce — with the real packet.packetHash — before handlers
        // dispatch, so Identity.recall(destHash) for outbound DIRECT sends
        // works without an app-side remember (a redundant Identity.remember
        // here previously overwrote the fork's entry with a zeroed packetHash).
        // The announced identity's hash is mapped to the peerId so the
        // offer-feed cross-check (handleOfferAnnounce) is order-independent.
        Transport.registerAnnounceHandler(
            handler = AnnounceHandler { destHash, announcedIdentity, appData ->
                handlePeerAnnounce(destHash, announcedIdentity, appData)
                false
            },
            aspectFilter = "lxmf.delivery",
        )
        // Phase 3: offer-feed announces (neop2p/offers aspect). The announce
        // appData is the compact offer digest; the announcing peer's identity
        // is cross-checked against the lxmf.delivery announce so a spoofed
        // digest cannot claim a peerId it does not own.
        Transport.registerAnnounceHandler(
            handler = AnnounceHandler { destHash, announcedIdentity, appData ->
                handleOfferAnnounce(destHash, announcedIdentity, appData)
                false
            },
            aspectFilter = "neop2p.offers",
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
        // Paced offer-feed re-announce: one digest per tick, round-robin
        // through the caller's open offers. The feed is otherwise announced
        // exactly once at create/edit (publishOffer), so a peer that joins
        // later — or a seller restarting with open offers — never rediscovers
        // them. One announce per tick keeps the per-destination announce rate
        // (MAX_RATE_TIMESTAMPS=16/30s in the fork) well under the limit; 100
        // offers cycle in ~4 minutes.
        scope.launch {
            while (isActive) {
                delay(if (idleMode) idleReannounceIntervalMs else offerReannounceIntervalMs)
                val dest = offersDest ?: continue
                val keys = offerDigestsById.keys.toList()
                if (keys.isEmpty()) {
                    // 2026-09-02 (3rd-device convergence): no live offers —
                    // re-announce the terminal tombstones so peers holding a
                    // stale row converge. One tombstone per tick keeps the
                    // same per-destination rate profile as the live loop.
                    announceTombstone(dest)
                    continue
                }
                // Bug B observability: the fork rate-limits announces to
                // MAX_RATE_TIMESTAMPS=16 per 30s per destination hash (all
                // offers share one dest). One announce per tick means the
                // per-30s rate is fixed by the tick — warn when the tick
                // itself approaches the ceiling, so a future cadence change
                // can't silently drop announces (1500ms dropped 8× in the
                // load test; 2500ms keeps 25% headroom).
                val effectiveTick = if (idleMode) idleReannounceIntervalMs else offerReannounceIntervalMs
                val announcesPer30s = (30_000L / effectiveTick).coerceAtLeast(1)
                if (announcesPer30s * 2 >= MAX_RATE_TIMESTAMPS_PER_DEST) {
                    println("[RnsSession] WARN: ${effectiveTick}ms tick ≈ $announcesPer30s " +
                        "offers announced /30s — within 2× of the fork's $MAX_RATE_TIMESTAMPS_PER_DEST/30s cap; " +
                        "a lower tick would drop announces")
                }
                // Round-robin: announce a different offer each tick so a large
                // open set still cycles through in bounded time (N offers ≈
                // N × tick per full cycle).
                val digest = offerDigestsById[keys[offerReannounceCursor % keys.size]] ?: continue
                offerReannounceCursor++
                pacedOfferReannounces++
                runCatching { dest.announce(digest.toByteArray(Charsets.UTF_8)) }
                // Tombstone cadence (2026-09-02): one tombstone per
                // TOMBSTONE_REANNOUNCE_TICKS live ticks, round-robin. With
                // the 2.5s tick that is ~3 tombstones/30s — combined with
                // the 12 live announces/30s the per-destination rate stays
                // at 15/30s, under the fork's 16/30s cap. (The previous
                // cursor-mod-live-size logic announced a tombstone EVERY tick
                // when the live set had one offer → 24/30s → the node
                // blocked the entire destination.)
                tombstoneReannounceCursor++
                if (tombstoneReannounceCursor % TOMBSTONE_REANNOUNCE_TICKS == 0) {
                    announceTombstone(dest)
                }
            }
        }
        println("[RnsSession] started (identity ${identity.hexHash.take(12)}…, dest ${deliveryDest!!.hexHash.take(12)}…)")
    }

    /**
     * Bring up a TCP client interface per node (registered + started). A
     * startup exception on one node must not stop the others — each
     * interface is independent and Transport keeps the healthy ones.
     */
    private fun connectTransportNodes(nodes: List<Pair<String, Int>>) {
        for ((host, port) in nodes) {
            val key = "$host:$port"
            if (tcpInterfaces.containsKey(key)) continue
            runCatching {
                val tcp = TCPClientInterface(
                    name = "TransportNode/$host:$port",
                    targetHost = host,
                    targetPort = port,
                    // TCP keepalive ON: firewalls/NATs drop idle connections
                    // after ~28s; keepalive probes + the 20s re-announce keep
                    // the link alive (observed 2026-08-31: connection dropped
                    // every ~28s with keepAlive=false).
                    keepAlive = true,
                )
                Transport.registerInterface(tcp.toRef())
                tcp.start()
                tcpInterfaces[key] = tcp
            }.onFailure { e ->
                println("[RnsSession] Failed to start TCP interface for $key: ${e.message}")
            }
        }
    }

    /**
     * Live-apply the transport-node set (Tier 3; called by the orchestrator
     * when the user edits the node list in Settings). Nodes that were added
     * are brought up; nodes that were removed are torn down (deregistered +
     * detached). No network restart: Transport serves already-registered
     * interfaces immediately and announces go out over the survivors.
     */
    fun applyTransportNodes(nodes: List<Pair<String, Int>>) {
        connectTransportNodes(nodes)
        val wanted = nodes.map { "${it.first}:${it.second}" }.toSet()
        for ((key, tcp) in tcpInterfaces.entries) {
            if (key !in wanted) {
                Transport.deregisterInterface(tcp.toRef())
                tcp.stop()
                tcpInterfaces.remove(key)
                println("[RnsSession] Removed transport node $key")
            }
        }
    }

    fun stop() {
        scope.cancel()
        tcpInterfaces.values.forEach { tcp ->
            Transport.deregisterInterface(tcp.toRef())
            tcp.stop()
        }
        tcpInterfaces.clear()
        autoInterface?.let { auto ->
            Transport.deregisterInterface(auto.toRef())
            auto.detach()
        }
        autoInterface = null
        // Deregister our destinations BEFORE stopping the router/Transport.
        // Transport.stop() clears the path/announce tables but NOT the
        // registered-destinations list (Transport.kt:359) — a leaked
        // destination makes a later session's announce for the SAME identity
        // (e.g. a test child JVM reusing a seed) look like a LOCAL destination
        // and get dropped ("Skipping announce for local destination"), so the
        // peer is never seen. This is the RnsSoakTest flake: the soak child
        // uses seed 61, the same identity a prior RnsSessionTest registers.
        deliveryDest?.let { Transport.deregisterDestination(it) }
        offersDest?.let { Transport.deregisterDestination(it) }
        router?.stop()
        router = null
        deliveryDest = null
        offersDest = null
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
     * Publish an offer to the RNS feed: announce the neop2p/offers destination
     * with a compact digest as appData. The full offer JSON is NOT in the
     * announce (RNS announce appData is capped at ~300 bytes) — a peer that
     * wants the full offer requests it over LXMF (see [sendOfferRequest]).
     */
    fun publishOffer(digestJson: String): Result<Unit> = runCatching {
        val dest = offersDest ?: throw IllegalStateException("RNS offers destination not registered")
        dest.announce(digestJson.toByteArray(Charsets.UTF_8))
    }

    /**
     * Register a digest for the paced offer re-announce loop. The digest
     * contains the offer id (the commitment format `{v,id,h}`), which is used
     * as the map key so a caller can update it in place (edit) or remove it
     * (delete / terminal status).
     */
    fun trackOfferDigest(digestJson: String) {
        val decoded = RnsOfferDigest.decode(digestJson) ?: return
        val offerId = RnsOfferDigest.offerIdOf(decoded) ?: return
        offerDigestsById[offerId] = digestJson
    }

    /**
     * Stop re-announcing an offer (deleted / MATCHED / terminal status).
     */
    fun untrackOfferDigest(offerId: String) {
        offerDigestsById.remove(offerId)
    }

    /**
     * Pull-to-refresh: re-announce every tracked offer digest NOW instead of
     * waiting for the paced loop's next tick, and re-announce the delivery
     * destination so peers learn our fresh path. The burst is capped at
     * [MAX_RATE_TIMESTAMPS_PER_DEST] announces — the fork's per-destination
     * 30s cap — so a large open set can never trip the rate limiter; the
     * paced loop covers the remainder on subsequent ticks.
     */
    fun refreshFeed() {
        val dest = offersDest ?: return
        val keys = offerDigestsById.keys.toList()
        var announced = 0
        for (key in keys) {
            if (announced >= MAX_RATE_TIMESTAMPS_PER_DEST) break
            val digest = offerDigestsById[key] ?: continue
            runCatching { dest.announce(digest.toByteArray(Charsets.UTF_8)) }
            announced++
            pacedOfferReannounces++
        }
        // 2026-09-02 (3rd-device convergence): also re-announce the terminal
        // tombstones NOW so peers holding a stale row converge immediately
        // (the paced loop only re-announces them once per live-cycle).
        if (announced < MAX_RATE_TIMESTAMPS_PER_DEST) {
            for (key in terminalTombstonesById.keys) {
                if (announced >= MAX_RATE_TIMESTAMPS_PER_DEST) break
                val digest = terminalTombstonesById[key] ?: continue
                runCatching { dest.announce(digest.toByteArray(Charsets.UTF_8)) }
                announced++
                pacedTombstoneReannounces++
            }
        }
        val lxmf = router
        if (lxmf != null) {
            val delivery = deliveryDest
            if (delivery != null) runCatching { lxmf.announce(delivery) }
        }
        println("[RnsSession] refreshFeed: re-announced $announced offer digest(s) + delivery dest")
    }

    /**
     * Replace the tracked open-offer set wholesale (cold-start re-hydration).
     * Used by the orchestrator to seed the paced loop from the durable offer
     * table after a restart, so a seller's open offers are rediscoverable
     * without an edit.
     */
    fun setOpenOfferDigests(digests: Map<String, String>) {
        offerDigestsById.clear()
        offerDigestsById.putAll(digests)
        offerReannounceCursor = 0
    }

    /**
     * Replace the terminal-tombstone set wholesale (2026-09-02, 3rd-device
     * convergence). Called by the orchestrator's sweep re-hydration so
     * COMPLETED/CANCELLED offers keep re-announcing a tombstone digest even
     * across app restarts. Offer ids are timestamp-based and never reused, so
     * a live offer can never collide with a tombstone key.
     */
    fun setTerminalTombstones(tombstones: Map<String, String>) {
        terminalTombstonesById.clear()
        terminalTombstonesById.putAll(tombstones)
    }

    /**
     * Re-announce one terminal tombstone (round-robin). Digest-only
     * `{v,id,t}` — carries no status (G1).
     */
    private fun announceTombstone(dest: Destination) {
        val tombKeys = terminalTombstonesById.keys.toList()
        if (tombKeys.isEmpty()) return
        val digest = terminalTombstonesById[tombKeys[offerReannounceCursor % tombKeys.size]] ?: return
        offerReannounceCursor++
        pacedTombstoneReannounces++
        runCatching { dest.announce(digest.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Request the full offer JSON from [toPeerId] over LXMF (DIRECT). The
     * peer's offer-feed announce only carries the digest; the full JSON is
     * fetched on demand so the feed stays within announce size limits.
     */
    fun sendOfferRequest(toPeerId: String, offerId: String): Result<Unit> =
        sendSignaling(toPeerId, "offer_request", "{\"offer_id\":\"$offerId\"}")

    /**
     * Send the full offer JSON to [toPeerId] over LXMF (DIRECT) in response
     * to an offer_request.
     */
    fun sendOffer(toPeerId: String, offerJson: String): Result<Unit> =
        sendSignaling(toPeerId, "offer", offerJson)

    /**
     * Send an offer status update (MATCHED/ESCROWED/PAUSED/OPEN) to
     * [toPeerId] over LXMF (DIRECT). Mirrors LXMF offer_status for the RNS path.
     */
    fun sendOfferStatus(
        toPeerId: String,
        offerId: String,
        status: String,
        matchedPeerId: String? = null,
        buyerBtcAddress: String? = null,
        authorPeerId: String? = null,
    ): Result<Unit> = sendSignaling(
        toPeerId,
        "offer_status",
        buildString {
            append("{\"offer_id\":\"").append(offerId).append("\"")
            append(",\"status\":\"").append(status).append("\"")
            matchedPeerId?.let { append(",\"matched_peer_id\":\"").append(it).append("\"") }
            buyerBtcAddress?.takeIf { it.isNotBlank() }?.let { append(",\"buyer_btc_address\":\"").append(it).append("\"") }
            authorPeerId?.takeIf { it.isNotBlank() }?.let { append(",\"author_peer_id\":\"").append(it).append("\"") }
            append("}")
        }
    )

    /**
     * Tell [toPeerId] that an offer was deleted (tombstone propagation).
     * The peer removes the row and tombstones it so a later re-announce of
     * the original offer cannot resurrect it. Deletion was local-only
     * before — peers that had already ingested the offer kept it forever.
     */
    fun sendOfferDelete(toPeerId: String, offerId: String): Result<Unit> =
        sendSignaling(toPeerId, "offer_delete", "{\"offer_id\":\"$offerId\"}")

    /**
     * Send a signed attestation to [toPeerId] over LXMF (DIRECT). Mirrors
     * offer_status: title = "attestation", FIELD_CUSTOM_DATA = the JSON
     * built by ReputationSystem.toWireJson. Failed deliveries re-queue via
     * RESENDABLE_TYPES and resend on the peer's next announce.
     */
    fun sendAttestation(toPeerId: String, json: String): Result<Unit> =
        sendSignaling(toPeerId, "attestation", json)

    /**
     * Send an escrow status sync to [toPeerId] over LXMF (DIRECT). Mirrors
     * LXMF escrow_status for the RNS path. [fields] is the same mutable-field map the
     * Nostr path publishes.
     */
    fun sendEscrowStatus(
        toPeerId: String,
        escrowId: String,
        status: String,
        fields: Map<String, String>,
    ): Result<Unit> = sendSignaling(
        toPeerId,
        "escrow_status",
        buildString {
            append("{\"escrow_id\":\"").append(escrowId).append("\"")
            append(",\"status\":\"").append(status).append("\"")
            fields.forEach { (k, v) -> append(",\"").append(k).append("\":\"").append(v.replace("\"", "\\\"")).append("\"") }
            append("}")
        }
    )

    /**
     * Send a dispute-opened event to [toPeerId] over LXMF (DIRECT). Mirrors
     * LXMF dispute message for the RNS path. [fields] carries the same payload the
     * Nostr path publishes (redeem script, psbt, refund tx, ...).
     */
    fun sendDispute(
        toPeerId: String,
        escrowId: String,
        openedBy: String,
        reason: String,
        fields: Map<String, String>,
    ): Result<Unit> = sendSignaling(
        toPeerId,
        "dispute",
        buildString {
            append("{\"escrow_id\":\"").append(escrowId).append("\"")
            append(",\"opened_by\":\"").append(openedBy).append("\"")
            append(",\"reason\":\"").append(reason.replace("\"", "\\\"")).append("\"")
            append(",\"opened_at\":").append(System.currentTimeMillis())
            fields.forEach { (k, v) -> append(",\"").append(k).append("\":\"").append(v.replace("\"", "\\\"")).append("\"") }
            append("}")
        }
    )

    /**
     * Send dispute evidence to [toPeerId] over LXMF (DIRECT). Mirrors
     * LXMF evidence message for the RNS path. The image rides as an LXMF file
     * attachment (auto-Resource for >319B), the description as a field.
     */
    fun sendEvidence(
        toPeerId: String,
        escrowId: String,
        submitter: String,
        description: String,
        mimeType: String,
        imageBytes: ByteArray,
    ): Result<Unit> = runCatching {
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
        val meta = buildString {
            append("{\"escrow_id\":\"").append(escrowId).append("\"")
            append(",\"submitter\":\"").append(submitter).append("\"")
            append(",\"description\":\"").append(description.replace("\"", "\\\"")).append("\"")
            append(",\"mime_type\":\"").append(mimeType).append("\"")
            // The image rides BOTH as a file attachment (counterparty chat
            // bubble) AND as base64 in the meta — the arbitrator's dispute
            // feed persists it via applyEvidenceEvent, which reads
            // image_base64 (P2POrchestrator). Images are UI-capped at 60KB →
            // ≤80KB base64, inside the 256KB inbound cap and the 80KB
            // MAX_EVIDENCE_BASE64_CHARS gate.
            append(",\"image_base64\":\"").append(java.util.Base64.getEncoder().encodeToString(imageBytes)).append("\"")
            append("}")
        }
        val msg = LXMessage.create(
            destination = dest,
            source = source,
            content = "",
            title = "evidence",
            fields = mutableMapOf(
                LXMFConstants.FIELD_CUSTOM_DATA to meta.toByteArray(Charsets.UTF_8),
                LXMFConstants.FIELD_FILE_ATTACHMENTS to
                    listOf(listOf("evidence.jpg".toByteArray(Charsets.UTF_8), imageBytes)),
            ),
            desiredMethod = DeliveryMethod.DIRECT,
        )
        runBlocking { lxmf.handleOutbound(msg) }
    }

    /**
     * Send an arbitration resolution to [toPeerId] over LXMF (DIRECT).
     * Mirrors LXMF resolution message for the RNS path.
     */
    fun sendResolution(
        toPeerId: String,
        escrowId: String,
        decision: String,
        arbitratorSigHex: String,
        notes: String?,
        sellerRefundAddress: String?,
        signedTxHex: String?,
    ): Result<Unit> = sendSignaling(
        toPeerId,
        "resolution",
        buildString {
            append("{\"escrow_id\":\"").append(escrowId).append("\"")
            append(",\"decision\":\"").append(decision).append("\"")
            append(",\"arbitrator_sig_hex\":\"").append(arbitratorSigHex).append("\"")
            notes?.let { append(",\"notes\":\"").append(it.replace("\"", "\\\"")).append("\"") }
            sellerRefundAddress?.takeIf { it.isNotBlank() }?.let { append(",\"seller_refund_address\":\"").append(it).append("\"") }
            signedTxHex?.takeIf { it.isNotBlank() }?.let { append(",\"signed_tx_hex\":\"").append(it).append("\"") }
            append("}")
        }
    )

    /** Shared DIRECT LXMF send for JSON signaling payloads. */
    private fun sendSignaling(toPeerId: String, type: String, json: String): Result<Unit> = runCatching {
        val lxmf = router ?: throw IllegalStateException("RNS not started")
        val data = json.toByteArray(Charsets.UTF_8)
        val destHex = destHashByPeerId[toPeerId]
            ?: run {
                // Send-time failure (counterparty not announced yet): queue
                // for retry on their next announce instead of silently
                // dropping (fixed 2026-09-07 — a MATCHED claim lost here
                // left the creator OPEN forever).
                queueResend(toPeerId, type, data)
                throw IllegalStateException("No RNS path to $toPeerId (peer has not announced)")
            }
        val destHash = hexToBytes(destHex)
        val peerIdentity = Identity.recall(destHash)
            ?: run {
                queueResend(toPeerId, type, data)
                throw IllegalStateException("Unknown RNS identity for $toPeerId")
            }
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
     * Queue a failed signaling payload for retry on the peer's next announce
     * (S05/S06). Shared by the LXMF failed-delivery callback (link died
     * mid-flight) and the send-time failure path (no path / unknown
     * identity yet). Eligibility + dedup key are the pure policy in
     * ResendQueue.kt — chat/pre-key are deliberately excluded (they ride
     * the durable OfflineQueue and would double-send).
     */
    private fun queueResend(peerId: String, type: String, data: ByteArray) {
        if (!resendQueueAllowed(type, data.size, RESENDABLE_TYPES, MAX_RESEND_PAYLOAD_BYTES)) return
        pendingResends[resendQueueKey(peerId, type, data)] = PendingResend(peerId, type, data)
    }

    /**
     * Parse a peer's LXMF delivery announce appData (msgpack
     * `[displayName, stampCost]`) and record the peerId <-> dest hash mapping.
     * The announced identity's hash is also mapped to the peerId so offer
     * digests from the same identity can be cross-checked (see
     * [handleOfferAnnounce]) without recalling the delivery identity.
     * Also invoked directly by tests (Transport skips announces for local
     * destinations, so the in-JVM loopback test feeds it by hand).
     */
    internal fun handlePeerAnnounce(destHash: ByteArray, announcedIdentity: Identity?, appData: ByteArray?) {
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
                    announcedIdentity?.let { identity ->
                        peerIdByIdentityHash[identity.hash.toHexString()] = peerId
                        // Flush offer digests that arrived before this
                        // delivery announce (see handleOfferAnnounce).
                        val held = pendingOfferAnnouncesByIdentityHash.remove(identity.hash.toHexString())
                        if (held != null) {
                            for (digestJson in held.digests) {
                                _offerAnnounces.tryEmit(OfferAnnounce(peerId, digestJson))
                            }
                        }
                    }
                    lastSeenByPeerId[peerId] = System.currentTimeMillis()
                    println("[RnsSession] Peer seen: $peerId (dest ${destHex.take(12)}…)")
                    _peerSeen.tryEmit(peerId)
                    resendFailedSignaling(peerId)
                }
            }
        } catch (e: Exception) {
            println("[RnsSession] Failed to parse announce appData: ${e.message}")
        }
    }

    /**
     * Handle a neop2p/offers announce: the appData is the compact offer
     * digest. The announcing identity is cross-checked against the peer's
     * lxmf.delivery announce (same RNS identity ⇒ same peerId) so a spoofed
     * digest cannot claim a peerId it does not own. Emits [OfferAnnounce].
     */
    internal fun handleOfferAnnounce(destHash: ByteArray, announcedIdentity: Identity, appData: ByteArray?) {
        if (appData == null || appData.isEmpty()) return
        val digestJson = String(appData, Charsets.UTF_8)
        if (digestJson.isBlank()) return
        // The offers destination is derived from OUR identity — a peer's
        // offers announce carries THEIR identity. Map it to a peerId via the
        // identity hash captured from the lxmf.delivery announce (same RNS
        // identity ⇒ same peerId). A digest can beat its delivery announce
        // (transport re-announce ordering / burst); when that happens the
        // digest is held and flushed once the delivery announce lands
        // (found by RnsLoadTest 2026-09-01).
        val identityHashHex = announcedIdentity.hash.toHexString()
        val peerId = peerIdByIdentityHash[identityHashHex]
        if (peerId == null) {
            println("[RnsSession] Offer announce from unannounced identity — deferring (identity ${identityHashHex.take(8)}…)")
            deferOfferAnnounce(identityHashHex, digestJson)
            return
        }
        _offerAnnounces.tryEmit(OfferAnnounce(peerId, digestJson))
    }

    /**
     * Hold a digest that arrived before its peer's delivery announce, bounded
     * (Bug A2): ≤ [MAX_PENDING_OFFERS_PER_IDENTITY] per identity (deduped,
     * insertion order), ≤ [MAX_PENDING_OFFER_IDENTITIES] identities (oldest
     * evicted). A hostile peer under a never-delivering identity can no
     * longer grow memory without limit.
     */
    private fun deferOfferAnnounce(identityHashHex: String, digestJson: String) {
        synchronized(pendingOfferAnnouncesByIdentityHash) {
            if (pendingOfferAnnouncesByIdentityHash.size >= MAX_PENDING_OFFER_IDENTITIES &&
                !pendingOfferAnnouncesByIdentityHash.containsKey(identityHashHex)
            ) {
                // Evict the oldest identity (by first deferral) before adding.
                val oldest = pendingOfferAnnouncesByIdentityHash.values
                    .minByOrNull { it.firstSeenMs }
                oldest?.let { pendingOfferAnnouncesByIdentityHash.remove(it.identityHashHex) }
                println("[RnsSession] deferral cap ($MAX_PENDING_OFFER_IDENTITIES identities) — evicting oldest")
            }
            val entry = pendingOfferAnnouncesByIdentityHash.getOrPut(identityHashHex) {
                PendingOfferAnnounces(identityHashHex, System.currentTimeMillis())
            }
            if (entry.digests.size >= MAX_PENDING_OFFERS_PER_IDENTITY) {
                println("[RnsSession] deferral cap ($MAX_PENDING_OFFERS_PER_IDENTITY digests/identity) — dropping digest")
                return
            }
            entry.digests.add(digestJson)
        }
    }

    /**
     * Test seam: number of identities with deferred offer digests (bounded by
     * [MAX_PENDING_OFFER_IDENTITIES]). Lets in-JVM tests verify the hostile
     * identity cap (Bug A2).
     */
    internal fun pendingDeferredIdentityCount(): Int =
        pendingOfferAnnouncesByIdentityHash.size

    /** Test seam: total deferred digests across all identities (bounded). */
    internal fun pendingDeferredDigestCount(): Int =
        pendingOfferAnnouncesByIdentityHash.values.sumOf { it.digests.size }

    /** Re-send signaling messages that failed on a dead DIRECT link, now that
     * [peerId] has announced a fresh path. Bounded: at most
     * [MAX_RESEND_ATTEMPTS] per message; dropped after that (the caller's
     * own retry machinery — republishLostClaims, PendingDisputeStore,
     * resume-heal — covers the critical ones).
     */
    private fun resendFailedSignaling(peerId: String) {
        val toResend = pendingResends.values.filter { it.toPeerId == peerId }
        for (pending in toResend) {
            if (pending.attempts >= MAX_RESEND_ATTEMPTS) {
                pendingResends.remove("$peerId|${pending.type}|${pending.data.contentHashCode()}")
                continue
            }
            pending.attempts++
            val ok = send(peerId, pending.data, pending.type).isSuccess
            if (ok) {
                pendingResends.remove("$peerId|${pending.type}|${pending.data.contentHashCode()}")
            }
        }
    }

    /** Test seam: inbound LXMF dispatch (private in production, internal for in-JVM tests). */
    internal fun handleInbound(msg: LXMessage) {
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
                    // I5: cap inbound file size — a hostile peer must not be
                    // able to force an unbounded allocation/disk write.
                    if (fileData.size > MAX_INBOUND_FILE_BYTES) {
                        println("[RnsSession] Dropping oversized file ${name.take(64)} (${fileData.size} bytes) from $peerId")
                        continue
                    }
                    _receivedFiles.tryEmit(ReceivedFile(peerId, name, fileData))
                    _incoming.tryEmit(Inbound("file", peerId, fileData))
                }
            }
            // Evidence messages carry the image as a file attachment AND the
            // meta (escrow_id/submitter/description) as FIELD_CUSTOM_DATA.
            // The orchestrator's applyEvidenceEvent needs the meta to persist
            // the evidence to the arbitrator's dispute feed — a file-only
            // emission would drop it and the evidence would never arrive.
            // Surface the meta as an Inbound("evidence", meta) so the
            // orchestrator's evidence handler fires (S75).
            if (msg.title == "evidence") {
                val meta = msg.fields[LXMFConstants.FIELD_CUSTOM_DATA] as? ByteArray
                if (meta != null && meta.size <= MAX_INBOUND_CUSTOM_DATA_BYTES) {
                    _incoming.tryEmit(Inbound("evidence", peerId, meta))
                }
            }
            return
        }

        // Regular envelope: title = app message type, FIELD_CUSTOM_DATA = the
        // app's EnvelopeCodec bytes (binary-safe; content is UTF-8 String).
        val data = msg.fields[LXMFConstants.FIELD_CUSTOM_DATA] as? ByteArray
            ?: msg.content.toByteArray(Charsets.UTF_8)
        // I5: cap inbound custom data — signaling JSON and chat envelopes are
        // small; a hostile peer must not force an unbounded allocation.
        if (data.size > MAX_INBOUND_CUSTOM_DATA_BYTES) {
            println("[RnsSession] Dropping oversized ${msg.title} payload (${data.size} bytes) from $peerId")
            return
        }
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
        // 20s: keeps the VPS TCP link alive (idle connections are dropped
        // after ~28s by the firewall/NAT) AND heals the startup announce race
        // (the first announce fires before the TCP link is up — the next
        // re-announce broadcasts on the live interface). Also makes peer
        // discovery fast: a peer that joins after our announce learns us
        // within one interval.
        private const val RE_ANNOUNCE_INTERVAL_MS = 20_000L

        /**
         * Paced offer-feed re-announce tick: one digest per tick, round-robin
         * through the caller's open offers. 2500ms = 12/30s per destination,
         * ~25% headroom under the fork's MAX_RATE_TIMESTAMPS=16/30s rebroadcast
         * cap (1500ms dropped announces in the load test; 2000ms was clean;
         * 2500ms is comfortably inside against scheduling jitter). 100 offers
         * cycle in ~4 minutes.
         */
        private const val OFFER_REANNOUNCE_INTERVAL_MS = 2_500L

        /** Idle (backgrounded) paced offer tick: 1 digest/60s vs 2.5s
         *  foreground. ~8,600 idle announces/day → ~1,440. Tombstones at
         *  1/240s still converge 3rd devices; rate cap (16/30s/dest) untouched. */
        private const val OFFER_REANNOUNCE_IDLE_INTERVAL_MS = 60_000L

        /**
         * 2026-09-02 (3rd-device convergence): tombstone re-announce cadence.
         * One tombstone per N live ticks keeps the combined live+tombstone
         * announce rate under the fork's per-destination cap (16/30s): 12
         * live + 3 tombstone = 15/30s at the 2.5s tick.
         */
        private const val TOMBSTONE_REANNOUNCE_TICKS = 4

        /**
         * Bug A2 (2026-09-01): bounds for the offer-digest deferral buffer — a
         * hostile peer announcing offers under an identity that never delivers
         * a delivery-announce must not grow memory without limit.
         */
        private const val MAX_PENDING_OFFERS_PER_IDENTITY = 32
        private const val MAX_PENDING_OFFER_IDENTITIES = 64

        /**
         * Bug B (2026-09-01): the fork's per-destination announce rate cap
         * (rns-core MAX_RATE_TIMESTAMPS). All offers share one destination
         * hash, so this is the ceiling for the paced loop's tick.
         */
        private const val MAX_RATE_TIMESTAMPS_PER_DEST = 16

        /** Signaling types re-queued after a failed DIRECT delivery (S05/S06). */
        private val RESENDABLE_TYPES = setOf(
            "offer_status", "offer_delete", "escrow_status", "dispute", "evidence",
            "resolution", "offer_request", "offer", "attestation",
        )

        /** Cap for re-queued signaling payloads (evidence images ride files). */
        private const val MAX_RESEND_PAYLOAD_BYTES = 16 * 1024

        /** Max re-send attempts per failed signaling message. */
        private const val MAX_RESEND_ATTEMPTS = 3

        /**
         * I5: inbound payload caps. Evidence images are capped at 60KB at the
         * UI (ReceiptComposer), so 512KB is generous headroom; signaling JSON
         * and chat envelopes are a few KB at most.
         */
        private const val MAX_INBOUND_FILE_BYTES = 512 * 1024
        private const val MAX_INBOUND_CUSTOM_DATA_BYTES = 256 * 1024

        /** Number of live RnsSession instances sharing the Reticulum singleton. */
        private val activeSessions = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
