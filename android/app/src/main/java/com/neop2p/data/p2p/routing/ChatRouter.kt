package com.neop2p.data.p2p.routing

import android.util.Log
import com.neop2p.data.local.dao.ChatMessageDao
import com.neop2p.data.local.entity.ChatMessageEntity
import com.neop2p.data.p2p.SignalProtocol
import com.neop2p.data.p2p.WebRTCManager
import com.neop2p.data.p2p.protocol.AppMessage
import com.neop2p.data.p2p.queue.OfflineQueue
import com.neop2p.ui.screens.chat.ChatMessage
import java.util.UUID
import javax.inject.Inject

class ChatRouter @Inject constructor(
    private val signal: SignalProtocol,
    private val queue: OfflineQueue,
    private val webRTCManager: WebRTCManager,
    private val chatMessageDao: ChatMessageDao
) {
    suspend fun sendText(peerId: String, offerId: String, plaintext: ByteArray): Result<Unit> {
        return signal.encrypt(peerId, plaintext)
            .onSuccess { ct ->
                queue.send(peerId, AppMessage.Chat(peerId, offerId, ct))
            }
            .map { Unit }
    }

    /**
     * Send a file over the WebRTC data channel, then persist a placeholder
     * message so both sides have a record. Returns the persisted message.
     */
    suspend fun sendFile(
        peerId: String,
        offerId: String,
        fileName: String,
        data: ByteArray
    ): Result<ChatMessage> {
        return webRTCManager.sendFile(peerId, fileName, data).map { sent ->
            val entity = ChatMessageEntity(
                message_id = UUID.randomUUID().toString(),
                offer_id = offerId,
                sender_peer_id = peerId,
                ciphertext = ByteArray(0),
                is_read = false,
                sent_at = System.currentTimeMillis(),
                file_attachment = data
            )
            chatMessageDao.insert(entity)
            ChatMessage(
                messageId = entity.message_id,
                offerId = offerId,
                senderPeerId = peerId,
                senderNickname = "",
                text = "[File: $fileName, ${data.size} bytes]",
                timestamp = entity.sent_at,
                isRead = false,
                fileAttachment = true
            )
        }
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

    /**
     * Load persisted chat history for an offer, decrypting each ciphertext
     * with the established session key. Messages whose peer key is gone
     * (session reset) are skipped rather than crashing history.
     */
    suspend fun loadHistory(offerId: String): List<ChatMessage> {
        val entities = chatMessageDao.getMessagesSync(offerId)
        val result = mutableListOf<ChatMessage>()
        for (entity in entities) {
            val plaintext = if (entity.ciphertext.isNotEmpty()) {
                signal.decrypt(entity.sender_peer_id, entity.ciphertext).getOrNull()
            } else {
                null
            }
            val text = if (entity.file_attachment != null) {
                "[File attachment, ${entity.file_attachment.size} bytes]"
            } else {
                plaintext?.toString(Charsets.UTF_8) ?: "[encrypted — session unavailable]"
            }
            result.add(
                ChatMessage(
                    messageId = entity.message_id,
                    offerId = entity.offer_id,
                    senderPeerId = entity.sender_peer_id,
                    senderNickname = "",
                    text = text,
                    timestamp = entity.sent_at,
                    isRead = entity.is_read,
                    fileAttachment = entity.file_attachment != null
                )
            )
        }
        return result
    }
}
