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
    private val offerDao: com.neop2p.data.local.dao.OfferDao,
    private val transport: com.neop2p.data.p2p.HybridP2PTransport
) {
    /** Offer ids whose payment details were already shared this process run. */
    private val paymentDetailsShared = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
                // The buyer's escrow detail screen renders the seller's bank
                // card straight from the local offer row — persist the inbound
                // E2EE payment-details envelope so the details survive even if
                // the chat is never opened. Only ever over E2EE chat (P0-1).
                if (isPaymentDetailsPayload(plain)) {
                    persistInboundPaymentDetails(msg.offerId, plain)
                }
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
            val plainText = plaintext?.toString(Charsets.UTF_8)
            val receipt = plainText?.let { parsePaymentReceiptPayload(it) }
            result.add(
                ChatMessage(
                    messageId = entity.message_id,
                    offerId = entity.offer_id,
                    senderPeerId = entity.sender_peer_id,
                    senderNickname = "",
                    // Structured receipts render as a card, not raw JSON.
                    text = if (receipt != null) "" else text,
                    timestamp = entity.sent_at,
                    isRead = entity.is_read,
                    fileAttachment = entity.file_attachment != null,
                    paymentDetails = isPayment,
                    paymentReceipt = receipt
                )
            )
        }
        return result
    }

    /** True if [plain] is our structured {"type":"payment_details",...} envelope. */
    private fun isPaymentDetailsPayload(plain: String): Boolean =
        plain.trimStart().startsWith("{\"type\":\"payment_details\"")

    /**
     * Build the E2EE payment-details envelope for an offer's stored bank
     * details: {"type":"payment_details","methods":{"bca":{...}}}. Shared
     * method with ChatScreen.sharePaymentDetails so the wire format stays
     * identical for manual and automatic shares.
     */
    private fun paymentDetailsPayload(details: Map<String, com.neop2p.domain.model.PaymentDetails>): String {
        val sb = StringBuilder("{\"type\":\"payment_details\",\"methods\":{")
        val entries = details.entries.toList()
        entries.forEachIndexed { index, entry ->
            if (index > 0) sb.append(",")
            val method = entry.key
            val d = entry.value
            sb.append("\"").append(method).append("\":{")
                .append("\"accountNumber\":\"").append(d.accountNumber).append("\"")
                .append(",\"accountHolder\":\"").append(d.accountHolder).append("\"")
                .append("}")
        }
        sb.append("}}")
        return sb.toString()
    }

    /**
     * Auto-share the seller's bank details with the buyer over E2EE chat the
     * moment the escrow becomes FUNDED. Best-effort + once per offer per
     * process run: if the peer is offline the message stays in the offline
     * queue and drains when they reconnect; a failed/no-session send must
     * never block or crash the caller (the buyer can re-request via the chat
     * screen's manual button).
     */
    suspend fun autoSharePaymentDetails(
        peerId: String,
        offerId: String,
        details: Map<String, com.neop2p.domain.model.PaymentDetails>
    ) {
        if (details.isEmpty() || !paymentDetailsShared.add(offerId)) return
        val payload = paymentDetailsPayload(details)
        signal.encrypt(peerId, payload.toByteArray(Charsets.UTF_8))
            .onSuccess { ct ->
                val msg = AppMessage.Chat(peerId, offerId, ct)
                queue.send(peerId, msg)
                queue.drainFor(peerId) { pending ->
                    val env = EnvelopeCodec.encode(pending)
                    transport.send(peerId, env.data, env.type).isSuccess
                }
                android.util.Log.d("ChatRouter", "Auto-shared payment details for offer $offerId")
            }
            .onFailure {
                android.util.Log.w("ChatRouter", "Auto-share payment details skipped: ${it.message}")
            }
    }

    /**
     * Persist an inbound payment-details envelope into the local offer row so
     * the BUYER's escrow detail screen can render the bank card without
     * needing the chat to be open. The payload arrived over E2EE chat (never
     * from the public relay), so storing it is consistent with P0-1. Returns
     * true when the offer row was updated.
     */
    suspend fun persistInboundPaymentDetails(offerId: String, plain: String): Boolean {
        if (!isPaymentDetailsPayload(plain)) return false
        return runCatching {
            val obj = org.json.JSONObject(plain.trimStart())
            if (obj.optString("type") != "payment_details") return@runCatching false
            val methods = obj.optJSONObject("methods") ?: return@runCatching false
            val parsed = mutableMapOf<String, com.neop2p.domain.model.PaymentDetails>()
            methods.keys().forEach { method ->
                val m = methods.optJSONObject(method) ?: return@forEach
                parsed[method] = com.neop2p.domain.model.PaymentDetails(
                    accountNumber = m.optString("accountNumber"),
                    accountHolder = m.optString("accountHolder")
                )
            }
            if (parsed.isEmpty()) return@runCatching false
            val entity = offerDao.getOfferSync(offerId) ?: return@runCatching false
            offerDao.upsert(
                entity.copy(
                    payment_details = com.neop2p.data.local.toPaymentDetailsJson(parsed.toMap())
                )
            )
            android.util.Log.i("ChatRouter", "Persisted inbound payment details for offer $offerId")
            true
        }.getOrDefault(false)
    }

    /**
     * Send a structured payment receipt (text card + optional E2EE image) to
     * the peer. Reuses [sendText], which E2EE-encrypts via [SignalProtocol]
     * before the message is queued/relayed — no new crypto path.
     */
    suspend fun sendReceiptMessage(
        offerId: String,
        peerId: String,
        payload: PaymentReceiptPayload
    ): Result<Unit> = sendText(peerId, offerId, payload.toJson().toByteArray(Charsets.UTF_8))
}

/** Structured E2EE payment receipt (text card + optional compressed screenshot). */
data class PaymentReceiptPayload(
    val reference: String,
    val amountSats: Long,
    val method: String,
    val sentAt: Long,
    val imageBase64: String? = null
) {
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"payment_receipt\",")
        sb.append("\"reference\":\"").append(reference).append("\",")
        sb.append("\"amountSats\":").append(amountSats).append(",")
        sb.append("\"method\":\"").append(method).append("\",")
        sb.append("\"sentAt\":").append(sentAt)
        if (imageBase64 != null) sb.append(",\"imageBase64\":\"").append(imageBase64).append("\"")
        sb.append("}")
        return sb.toString()
    }
}

fun parsePaymentReceiptPayload(json: String): PaymentReceiptPayload? {
    return try {
        val obj = org.json.JSONObject(json)
        if (obj.optString("type") != "payment_receipt") return null
        PaymentReceiptPayload(
            reference = obj.getString("reference"),
            amountSats = obj.getLong("amountSats"),
            method = obj.getString("method"),
            sentAt = obj.getLong("sentAt"),
            imageBase64 = obj.optString("imageBase64").takeIf { it.isNotEmpty() }
        )
    } catch (e: Exception) {
        null
    }
}
