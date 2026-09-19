package com.neop2p.admind

import com.neop2p.data.p2p.P2PTransport
import com.neop2p.data.p2p.RnsSession
import com.neop2p.data.p2p.ResolutionSender
import com.neop2p.data.p2p.TransportGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Bridges a headless [RnsSession] to the narrow [TransportGateway] the
 * [com.neop2p.data.p2p.ArbitrationReceiver] consumes. Mirrors the app's
 * `RnsTransport` inbound mapping (type / sender / dest hash) without any
 * Android dependency.
 */
class RnsTransportGateway(
    private val session: RnsSession,
    private val myPeerId: String,
    scope: CoroutineScope,
) : TransportGateway, ResolutionSender {

    private val _incoming =
        MutableSharedFlow<P2PTransport.TransportMessage>(extraBufferCapacity = 128)
    override val incomingMessages: SharedFlow<P2PTransport.TransportMessage> = _incoming

    private val forwardJob: Job = scope.launch {
        session.incoming.collect { inbound ->
            _incoming.emit(
                P2PTransport.TransportMessage(
                    type = inbound.type,
                    fromPeerId = inbound.fromPeerId,
                    toPeerId = myPeerId,
                    data = inbound.data,
                    authenticated = true,
                    senderDestHash = inbound.senderDestHash,
                )
            )
        }
    }

    override fun isVerifiedSender(peerId: String, senderDestHash: String): Boolean =
        session.isVerifiedSender(peerId, senderDestHash)

    override suspend fun sendResolution(
        toPeerId: String,
        escrowId: String,
        decision: String,
        arbitratorSigHex: String,
        notes: String?,
        sellerRefundAddress: String?,
        signedTxHex: String?,
    ): Boolean = session.sendResolution(
        toPeerId = toPeerId,
        escrowId = escrowId,
        decision = decision,
        arbitratorSigHex = arbitratorSigHex,
        notes = notes,
        sellerRefundAddress = sellerRefundAddress,
        signedTxHex = signedTxHex,
    ).isSuccess

    fun close() {
        forwardJob.cancel()
    }
}
