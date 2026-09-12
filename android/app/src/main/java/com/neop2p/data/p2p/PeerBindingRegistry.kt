package com.neop2p.data.p2p

import java.util.concurrent.ConcurrentHashMap

/**
 * Verified peerId <-> RNS destination registry (F1). Fed ONLY by verified
 * `neop2p.identity` announces. A verified peerId is stable (both keys derive
 * from the same seed) — an unverified announce must never rebind it.
 */
class PeerBindingRegistry {
    private val destByPeer = ConcurrentHashMap<String, String>()
    private val peerByDest = ConcurrentHashMap<String, String>()

    /** @return the previous dest for this peerId (null when new or unchanged). */
    fun record(peerId: String, destHashHex: String): String? {
        val previous = destByPeer.put(peerId, destHashHex)
        peerByDest[destHashHex] = peerId
        if (previous != null && previous != destHashHex) peerByDest.remove(previous, peerId)
        return previous?.takeIf { it != destHashHex }
    }

    fun isVerified(peerId: String, destHashHex: String): Boolean =
        peerId.isNotBlank() && peerByDest[destHashHex] == peerId

    fun verifiedDest(peerId: String): String? = destByPeer[peerId]
}
