package com.neop2p.data.p2p.routing

import com.neop2p.data.local.dao.ChatMessageDao
import com.neop2p.data.local.entity.ChatMessageEntity
import com.neop2p.data.p2p.SignalProtocol
import com.neop2p.data.p2p.WebRTCManager
import com.neop2p.data.p2p.protocol.AppMessage
import com.neop2p.data.p2p.protocol.EnvelopeCodec
import com.neop2p.data.p2p.queue.OfflineQueue
import com.neop2p.ui.screens.chat.ChatMessage
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ChatRouter @Inject constructor(
    private val signal: SignalProtocol,
    private val queue: OfflineQueue,
    private val webRTCManager: WebRTCManager,
    private val chatMessageDao: ChatMessageDao,
    private val transport: com.neop2p.data.p2p.HybridP2PTransport
) {
    /** A decrypted inbound chat, with the offer it belongs to. */
    data class IncomingChat(
        val fromPeerId: String,
        val offerId: String,
        val plaintext: ByteArray
    )

    // Notification signal for inbound chat. The offer id rides along so
    // notifications group per conversation and deep-link to the right thread.
    private val _incomingChats = MutableSharedFlow<IncomingChat>(replay = 0)
    val incomingChats: SharedFlow<IncomingChat> = _incomingChats.asSharedFlow()
    /**
     * Encrypt the message, persist it to the offline queue for offline/relay
     * reliability, then immediately attempt a live delivery over the transport.
     *
     * If the peer is reachable the queued row is drained (deleted) on success;
     * if the peer is offline, the row stays in the offline queue and is drained
     * later by [P2POrchestrator] when the peer comes online. This fixes the bug
     * where messages to an already-known peer were enqueued but never drained
     * (no new peer-online event fired to trigger the drain), so chat silently
     * never reached the counterparty.
     */
    suspend fun sendText(peerId: String, offerId: String, plaintext: ByteArray): Result<Unit> {
        return signal.encrypt(peerId, plaintext)
            .onSuccess { ct ->
                val msg = AppMessage.Chat(peerId, offerId, ct)
                queue.send(peerId, msg)
                // Try to deliver right away; if the peer is offline, drainFor
                // returns false, the row stays queued, and the drain path retries.
                queue.drainFor(peerId) { pending ->
                    val env = EnvelopeCodec.encode(pending)
                    transport.send(peerId, env.data, env.type).isSuccess
                }
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
                val plain = decrypted.plaintext.toString(Charsets.UTF_8)
                chatMessageDao.insert(
                    ChatMessageEntity(
                        message_id = UUID.randomUUID().toString(),
                        offer_id = msg.offerId,
                        sender_peer_id = msg.from,
                        ciphertext = msg.ciphertext
                    )
                )
                // Emit the notification signal with the REAL offer id (the
                // generic inbound collector in P2POrchestrator used a blank
                // offerId, which collapsed every chat into one notification
                // and deep-linked nowhere). The orchestrator suppresses these
                // while the app is foregrounded.
                _incomingChats.emit(IncomingChat(msg.from, msg.offerId, decrypted.plaintext))
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
            val isPayment = entity.file_attachment == null && plaintext != null &&
                isPaymentDetailsPayload(plaintext.toString(Charsets.UTF_8))
            result.add(
                ChatMessage(
                    messageId = entity.message_id,
                    offerId = entity.offer_id,
                    senderPeerId = entity.sender_peer_id,
                    senderNickname = "",
                    text = text,
                    timestamp = entity.sent_at,
                    isRead = entity.is_read,
                    fileAttachment = entity.file_attachment != null,
                    paymentDetails = isPayment
                )
            )
        }
        return result
    }

    /** True if [plain] is our structured {"type":"payment_details",...} envelope. */
    private fun isPaymentDetailsPayload(plain: String): Boolean =
        plain.trimStart().startsWith("{\"type\":\"payment_details\"")
}
