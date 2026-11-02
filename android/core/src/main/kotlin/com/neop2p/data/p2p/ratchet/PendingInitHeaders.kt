package com.neop2p.data.p2p.ratchet

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-peer `ratchet_init` headers awaiting delivery (M-3, 2026-11-03).
 *
 * The handshake used one process-wide slot, so two interleaved handshakes
 * overwrote each other's header and the peer could end up with a header that
 * did not match the persisted ratchet state. Keying by peerId keeps each
 * handshake's header with its own peer.
 *
 * `createSession` is serialized process-wide, so put/consume for one peer is
 * ordered; the map is concurrent only so a `consume` from the router thread can
 * never race a `put` into a corrupted structure.
 */
class PendingInitHeaders {
    private val headers = ConcurrentHashMap<String, ByteArray>()

    fun put(peerId: String, header: ByteArray) {
        headers[peerId] = header
    }

    fun consume(peerId: String): ByteArray? = headers.remove(peerId)
}
