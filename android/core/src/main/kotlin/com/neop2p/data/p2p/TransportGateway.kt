package com.neop2p.data.p2p

import kotlinx.coroutines.flow.SharedFlow

/**
 * The narrow transport surface the arbitration receive path needs. Deliberately
 * smaller than [P2PTransport] so the headless daemon (and tests) can supply a
 * minimal implementation without a full RNS session.
 */
interface TransportGateway {

    val incomingMessages: SharedFlow<P2PTransport.TransportMessage>

    /**
     * F1: true when [senderDestHash] maps to [peerId] AND its identity hash
     * matches the verified `neop2p.identity` binding. Unverified senders are
     * dropped before any arbitration ingest.
     */
    fun isVerifiedSender(peerId: String, senderDestHash: String): Boolean
}
