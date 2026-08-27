package com.neop2p.data.p2p.queue

import com.neop2p.data.local.dao.PendingMessageDao
import com.neop2p.data.local.entity.PendingMessageEntity
import com.neop2p.data.p2p.protocol.AppMessage
import com.neop2p.data.p2p.protocol.EnvelopeCodec
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class OfflineQueue @Inject constructor(
    private val dao: PendingMessageDao
) {
    suspend fun send(toPeerId: String, msg: AppMessage) {
        val env = EnvelopeCodec.encode(msg)
        dao.insert(
            PendingMessageEntity(
                message_id = UUID.randomUUID().toString(),
                to_peer_id = toPeerId,
                type = msg.type,
                payload = env.data
            )
        )
    }

    /** Drop all queued chat rows for a peer (stale envelopes superseded by the current send). */
    suspend fun purgeChatFor(peerId: String) {
        dao.deleteChatFor(peerId)
    }

    suspend fun drainFor(peerId: String, deliver: suspend (AppMessage) -> Boolean) {
        val snapshot = dao.pendingFor(peerId).first()
        for (entity in snapshot) {
            val env = com.neop2p.data.p2p.P2PTransport.TransportMessage(
                type = entity.type,
                fromPeerId = "",
                toPeerId = peerId,
                data = entity.payload
            )
            val msg = EnvelopeCodec.decode(env) ?: continue
            val ok = deliver(msg)
            if (ok) dao.delete(entity.message_id)
        }
    }
}
