package com.neop2p.data.p2p

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Common interface for all peer-to-peer transports.
 *
 * Implementations:
 *   - [LibP2PManager]: direct libp2p (TCP/WebSocket) — preferred for true P2P.
 *   - [P2PTransportManager]: WebSocket relay fallback for worst NAT/firewall cases.
 */
interface P2PTransport {

    data class TransportState(
        val isRunning: Boolean = false,
        val peerId: String = "",
        val connectedPeers: Int = 0,
        val relayConnected: Boolean = false,
        val transportType: String = "unknown"
    )

    data class TransportMessage(
        val type: String,
        val fromPeerId: String,
        val toPeerId: String = "",
        val topic: String = "",
        val data: ByteArray = byteArrayOf(),
        val authenticated: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TransportMessage) return false
            return type == other.type &&
                    fromPeerId == other.fromPeerId &&
                    toPeerId == other.toPeerId &&
                    topic == other.topic &&
                    authenticated == other.authenticated &&
                    data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + fromPeerId.hashCode()
            result = 31 * result + toPeerId.hashCode()
            result = 31 * result + topic.hashCode()
            result = 31 * result + authenticated.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    val state: StateFlow<TransportState>
    val incomingMessages: SharedFlow<TransportMessage>

    suspend fun start(): Result<Unit>
    suspend fun stop(): Result<Unit>

    suspend fun send(toPeerId: String, data: ByteArray, type: String = "chat"): Result<Unit>
    suspend fun publish(topic: String, data: ByteArray): Result<Unit>
    suspend fun subscribe(topic: String): Result<Unit>
    fun isDirect(): Boolean
}
