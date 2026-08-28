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
 * Used by P2PTransportManager to maintain a list of connected/discovered peers.
 * Replaces the old libp2p DHT-based peer discovery with a simple registry
 * populated by relay announcements and Nostr metadata.
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
    enum class ConnectionQuality { OFFLINE, RELAYED, DIRECT }

    data class PeerInfo(
        val peerId: String,
        val lastSeen: Long = System.currentTimeMillis(),
        val isOnline: Boolean = false,
        val relayAddress: String = "",
        val authenticated: Boolean = false
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
     */
    fun recordPeerSeen(peerId: String, authenticated: Boolean = false) {
        _peers.update { map ->
            val existing = map[peerId]
            map + (peerId to (existing?.copy(
                lastSeen = System.currentTimeMillis(),
                isOnline = true,
                authenticated = authenticated
            ) ?: PeerInfo(peerId = peerId, isOnline = true, authenticated = authenticated)))
        }
        _quality.update { it + (peerId to if (authenticated) ConnectionQuality.DIRECT else ConnectionQuality.RELAYED) }
    }

    /**
     * Current connection quality for a peer. Defaults to OFFLINE for unknown
     * peers and after [markAllOffline].
     */
    fun qualityOf(peerId: String): ConnectionQuality {
        return _quality.value[peerId] ?: ConnectionQuality.OFFLINE
    }

    /**
     * Mark a peer as offline.
     */
    fun markPeerOffline(peerId: String) {
        _peers.update { map ->
            map[peerId]?.let { info ->
                map + (peerId to info.copy(isOnline = false))
            } ?: map
        }
    }

    /**
     * Mark every known peer offline. Called when the WS relay drops — the
     * relay is the only presence channel, so a relay disconnect means no
     * peer can be assumed reachable. Inbound messages re-raise peers to
     * online on the next successful delivery.
     */
    fun markAllOffline() {
        _peers.update { map ->
            if (map.values.none { it.isOnline }) map
            else map.mapValues { (_, info) -> info.copy(isOnline = false) }
        }
        _quality.update { map ->
            if (map.isEmpty()) map
            else map.mapValues { (_, _) -> ConnectionQuality.OFFLINE }
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
     * Clear all peer data (privacy option).
     */
    fun clear() {
        _peers.value = emptyMap()
    }
}
