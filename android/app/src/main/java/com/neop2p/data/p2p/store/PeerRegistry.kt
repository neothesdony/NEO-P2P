package com.neop2p.data.p2p.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks known peers and their connection status.
 *
 * Used by the RNS transport to maintain a list of connected/discovered peers.
 * Phase 4: the libp2p/WS-relay population paths were removed; peers are
 * recorded from LXMF announces and offer-feed ingestion.
 */
@Singleton
class PeerRegistry @Inject constructor() {

    /**
     * How the peer is currently reachable. RELAYED = messages travel via the
     * WS relay (may die mid-trade); DIRECT = libp2p secure session (true P2P).
     * OFFLINE = no recent contact. This is NOT a holepunch pipeline — the
     * jvm-libp2p library has no DCUtR support, so DIRECT only happens when a
     * libp2p connection actually exists.
     */
    /**
     * How the peer is currently reachable.
     *
     * This is NOT a holepunch pipeline (jvm-libp2p has no DCUtR), so there is
     * no "punching" state — DIRECT only happens when a libp2p connection
     * actually exists, and the relay/punch transitions below are the honest
     * subset:
     *
     *   OFFLINE      — no recent contact / stale DIRECT revoked by a dropped link
     *   CONNECTING   — first contact seen, no transport evidence yet
     *   RELAYED      — messages travel via the WS relay (may die mid-trade)
     *   RECONNECTING — the relay dropped and is backing off; peer assumed down
     *   RELAY_QUOTA  — the relay reported delivery failure / quota exhaustion
     *   DIRECT       — live libp2p secure session (true P2P)
     */
    enum class ConnectionQuality { OFFLINE, CONNECTING, RELAYED, RECONNECTING, RELAY_QUOTA, DIRECT }

    data class PeerInfo(
        val peerId: String,
        val lastSeen: Long = System.currentTimeMillis(),
        val isOnline: Boolean = false,
        val relayAddress: String = "",
        val authenticated: Boolean = false,
        val multiaddrs: List<String> = emptyList()
    ) {
        val isVerified: Boolean get() = authenticated
    }

    private val _peers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    val peers: StateFlow<Map<String, PeerInfo>> = _peers.asStateFlow()

    private val _quality = MutableStateFlow<Map<String, ConnectionQuality>>(emptyMap())
    val quality: StateFlow<Map<String, ConnectionQuality>> = _quality.asStateFlow()

    /**
     * Record that a peer was seen (from relay peer_list or incoming message).
     * [authenticated] marks whether the peer was verified over a secure
     * session — which is exactly the transport discriminator: libp2p secure
     * sessions set true (DIRECT), the WS relay sets false (RELAYED).
     * [multiaddrs] are the dial-able libp2p addresses learned from the offer
     * feed (OfferRouter calls this; Phase-2 dialing consumes it).
     */
    fun recordPeerSeen(
        peerId: String,
        authenticated: Boolean = false,
        multiaddrs: List<String> = emptyList()
    ) {
        _peers.update { map ->
            val existing = map[peerId] ?: PeerInfo(peerId = peerId)
            val mergedAddrs = if (multiaddrs.isNotEmpty()) multiaddrs else existing.multiaddrs
            map + (peerId to existing.copy(
                lastSeen = System.currentTimeMillis(),
                isOnline = true,
                authenticated = authenticated,
                multiaddrs = mergedAddrs
            ))
        }
        // Quality is monotonic: a libp2p secure session (authenticated=true)
        // is the strongest evidence and must never be downgraded by later
        // relay traffic (peer_list, relay-echoed messages). Relay contact
        // only sets RELAYED for peers we have no better evidence about.
        _quality.update { map ->
            when {
                authenticated -> map + (peerId to ConnectionQuality.DIRECT)
                // Relay contact upgrades CONNECTING/RECONNECTING/RELAY_QUOTA back
                // to RELAYED, but never downgrades a live DIRECT claim (a peer
                // that had a secure session is still DIRECT until the libp2p
                // connection actually drops, which markPeerOffline handles).
                map[peerId] == ConnectionQuality.DIRECT -> map
                else -> map + (peerId to ConnectionQuality.RELAYED)
            }
        }
    }

    /**
     * Current connection quality for a peer. Defaults to OFFLINE for unknown
     * peers and after [markAllOffline].
     */
    fun qualityOf(peerId: String): ConnectionQuality {
        return _quality.value[peerId] ?: ConnectionQuality.OFFLINE
    }

    /**
     * Mark a peer as offline and downgrade its quality from DIRECT to OFFLINE.
     *
     * DIRECT must only be claimed while a live libp2p connection exists. The
     * relay never downgrades a DIRECT peer (monotonic rule in [recordPeerSeen]),
     * so an explicit downgrade is the ONLY thing that can revoke a stale DIRECT
     * claim when the underlying libp2p link drops. The relay presence channel
     * re-raises the peer to RELAYED on the next heartbeat/peer_list, which keeps
     * the escrow relay-gate honest.
     */
    fun markPeerOffline(peerId: String) {
        _peers.update { map ->
            map[peerId]?.let { info ->
                map + (peerId to info.copy(isOnline = false))
            } ?: map
        }
        _quality.update { map ->
            if (map[peerId] == ConnectionQuality.DIRECT) {
                map + (peerId to ConnectionQuality.OFFLINE)
            } else {
                map
            }
        }
    }

    /**
     * Mark every known peer as RECONNECTING. Called when the transport drops —
     * no peer can be assumed reachable; the backoff loop will re-raise
     * reachable peers. A live DIRECT claim (an actual LXMF link) is preserved.
     */
    fun markAllOffline() {
        _peers.update { map ->
            if (map.values.none { it.isOnline }) map
            else map.mapValues { (_, info) -> info.copy(isOnline = false) }
        }
        _quality.update { map ->
            if (map.isEmpty()) map
            else map.mapValues { (_, q) ->
                if (q == ConnectionQuality.DIRECT) q else ConnectionQuality.RECONNECTING
            }
        }
    }

    /**
     * Mark a peer RELAY_QUOTA — the relay explicitly failed to deliver to it
     * (peer not found / quota exhausted / relay refused). Money actions on this
     * peer are gated the same as any non-DIRECT link. A later successful relay
     * delivery (recordPeerSeen) re-raises it to RELAYED.
     */
    fun markPeerQuotaExceeded(peerId: String) {
        if (peerId.isBlank()) return
        _quality.update { map ->
            when (map[peerId]) {
                ConnectionQuality.DIRECT -> map
                else -> map + (peerId to ConnectionQuality.RELAY_QUOTA)
            }
        }
    }

    /**
     * True only if the peer exists AND is currently marked online.
     */
    fun isPeerOnline(peerId: String): Boolean {
        return _peers.value[peerId]?.isOnline == true
    }

    /**
     * Get the number of currently online peers.
     */
    fun connectedPeerCount(): Int {
        return _peers.value.count { it.value.isOnline }
    }

    /**
     * Check if a specific peer is known.
     */
    fun isPeerKnown(peerId: String): Boolean {
        return _peers.value.containsKey(peerId)
    }

    /**
     * True only if the peer exists AND was recorded as authenticated over a
     * secure session. Never treat a peer seen only via the unauthenticated WS
     * relay as verified.
     */
    fun isPeerAuthenticated(peerId: String): Boolean {
        return _peers.value[peerId]?.authenticated == true
    }

    /**
     * Dial-able libp2p multiaddrs for a peer, learned from the offer feed.
     * Empty when the peer is unknown or never advertised any.
     */
    fun multiaddrsOf(peerId: String): List<String> {
        return _peers.value[peerId]?.multiaddrs ?: emptyList()
    }

    /**
     * Clear all peer data (privacy option).
     */
    fun clear() {
        _peers.value = emptyMap()
        _quality.value = emptyMap()
    }
}
