package com.neop2p.data.p2p.routing

import com.neop2p.data.local.dao.ChatMessageDao
import com.neop2p.data.local.entity.ChatMessageEntity
import com.neop2p.data.p2p.SignalProtocol
import com.neop2p.data.p2p.protocol.AppMessage
import com.neop2p.data.p2p.queue.OfflineQueue
import java.util.UUID
import javax.inject.Inject

class ChatRouter @Inject constructor(
    private val signal: SignalProtocol,
    private val queue: OfflineQueue,
    private val chatMessageDao: ChatMessageDao
) {
    suspend fun sendText(peerId: String, offerId: String, plaintext: ByteArray): Result<Unit> {
        return signal.encrypt(peerId, plaintext)
            .onSuccess { ct ->
                queue.send(peerId, AppMessage.Chat(peerId, offerId, ct.serialize()))
            }
            .map { Unit }
    }

    suspend fun receiveChat(msg: AppMessage.Chat): Result<Unit> {
        return signal.handleIncomingMessage(msg.from, msg.ciphertext)
            .onSuccess { decrypted ->
                chatMessageDao.insert(
                    ChatMessageEntity(
                        message_id = UUID.randomUUID().toString(),
                        offer_id = msg.offerId,
                        sender_peer_id = msg.from,
                        ciphertext = msg.ciphertext
                    )
                )
            }
            .map { Unit }
    }
}
