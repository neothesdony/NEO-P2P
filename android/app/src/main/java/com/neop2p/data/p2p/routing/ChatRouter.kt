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
    private val transport: com.neop2p.data.p2p.HybridP2PTransport,
    private val rnsTransport: com.neop2p.data.p2p.RnsTransport
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
    suspend fun sendText(peerId: String, offerId: String, plaintext: ByteArray): Result<Boolean> {
        var delivered = false
        return signal.encrypt(peerId, plaintext)
            .onSuccess { ct ->
                val msg = AppMessage.Chat(peerId, offerId, ct)
                queue.send(peerId, msg)
                // Try to deliver right away; if the peer is offline, drainFor
                // returns false, the row stays queued, and the drain path retries.
                queue.drainFor(peerId) { pending ->
                    val env = EnvelopeCodec.encode(pending)
                    // RNS first (Phase 2 dual-run): LXMF DIRECT delivery once
                    // the peer has announced. Fall back to the legacy hybrid
                    // transport (libp2p direct → WS relay).
                    val rnsOk = rnsTransport.send(peerId, env.data, env.type).isSuccess
                    if (rnsOk) {
                        delivered = true
                        return@drainFor true
                    }
                    val ok = transport.send(peerId, env.data, env.type).isSuccess
                    if (ok) delivered = true
                    ok
                }
            }
            // true = delivered live (peer reachable), false = queued for later
            // (peer offline). The caller shows "✓ Terkirim" vs "Menunggu rekan online".
            .map { delivered }
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
        // Phase 2: LXMF attachments first (auto-Resource for >319B). WebRTC
        // stays as the fallback until Phase 4 teardown.
        val rnsOk = rnsTransport.sendFile(peerId, fileName, data).isSuccess
        if (rnsOk) {
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
            return Result.success(
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
            )
        }
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
        android.util.Log.d("ChatRouter", "receiveChat: ciphertext=${msg.ciphertext.size} bytes from ${msg.from} offer=${msg.offerId} hex=${msg.ciphertext.take(24).joinToString("") { "%02x".format(it) }}")
        // Relay-replay dedup: relays re-send every stored event on each
        // (re)connect (NIP-01 REQ replay). A message that was already
        // processed must NOT be decrypted/persisted/notified again — the
        // ciphertext is unique per plaintext+session, so an exact match in
        // chat_messages means "seen already" (the old empty payment envelope
        // was being re-delivered on every reconnect). Persisted BEFORE the
        // decryption work so a crash mid-handling can't re-trigger it.
        if (chatMessageDao.countByCiphertext(msg.ciphertext) > 0) {
            android.util.Log.d("ChatRouter", "Dropped replay chat from ${msg.from} (ciphertext already seen)")
            return Result.success(Unit)
        }
        return signal.handleIncomingMessage(msg.from, msg.ciphertext)
            .onSuccess { decrypted ->
                val plain = decrypted.plaintext.toString(Charsets.UTF_8)
                android.util.Log.i("ChatRouter", "Inbound chat from ${msg.from}: ${plain.length} bytes, isPaymentDetails=${isPaymentDetailsPayload(plain)}, preview=${plain.take(60)}")
                // Structured payment-details envelopes are NOT chat: they are
                // rendered from the offer row (escrow screen), never from chat
                // history. Skipping the insert keeps a backlog flush (or relay
                // replay) from spamming chat with machine payloads.
                if (isPaymentDetailsPayload(plain)) {
                    persistInboundPaymentDetails(msg.offerId, plain)
                } else {
                    chatMessageDao.insert(
                        ChatMessageEntity(
                            message_id = UUID.randomUUID().toString(),
                            offer_id = msg.offerId,
                            sender_peer_id = msg.from,
                            ciphertext = msg.ciphertext
                        )
                    )
                    _incomingChats.emit(IncomingChat(msg.from, msg.offerId, decrypted.plaintext))
                }
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
            // Structured payment-details envelopes are NOT chat messages — the
            // bank card renders from the offer row (escrow detail screen).
            // Skip them in history so old machine rows (persisted by earlier
            // builds before the skip-insert fix) never appear as cards.
            val plainText = plaintext?.toString(Charsets.UTF_8)
            if (plainText != null && isPaymentDetailsPayload(plainText)) continue
            val receipt = plainText?.let { parsePaymentReceiptPayload(it) }
            val reject = plainText?.let { parsePaymentReceiptRejectPayload(it) }
            result.add(
                ChatMessage(
                    messageId = entity.message_id,
                    offerId = entity.offer_id,
                    senderPeerId = entity.sender_peer_id,
                    senderNickname = "",
                    // Structured receipts/rejects render as a card, not raw JSON.
                    text = if (receipt != null || reject != null) "" else text,
                    timestamp = entity.sent_at,
                    isRead = entity.is_read,
                    fileAttachment = entity.file_attachment != null,
                    paymentReceipt = receipt,
                    paymentReject = reject
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
        if (details.isEmpty()) return
        // Mark shared ONLY after a successful encrypt — otherwise a failed
        // send (session not yet established) would permanently skip the
        // share, and the buyer would never see the bank details.
        if (paymentDetailsShared.contains(offerId)) return
        sendPaymentDetails(peerId, offerId, details) {
            paymentDetailsShared.add(offerId)
            android.util.Log.d("ChatRouter", "Auto-shared payment details for offer $offerId")
        }
    }

    /**
     * Re-send payment details on the periodic sweep, bypassing the in-memory
     * dedup. The relay's "send" is fire-and-forget: the frame write succeeds
     * even when the peer is offline, the drain row is deleted, and the message
     * is lost. Re-encrypting (fresh nonce) + re-queuing every sweep guarantees
     * eventual delivery; the buyer dedups by content, so no chat spam.
     */
    suspend fun resendPaymentDetails(
        peerId: String,
        offerId: String,
        details: Map<String, com.neop2p.domain.model.PaymentDetails>
    ) {
        if (details.isEmpty()) return
        // Purge any stale queued chat rows for this peer before re-queueing:
        // over hours of broken builds the FIFO queue accumulated garbage
        // envelopes that drain ONE per sweep — the fresh message behind them
        // starves. The current offer's details supersede all older rows.
        queue.purgeChatFor(peerId)
        sendPaymentDetails(peerId, offerId, details) {
            android.util.Log.d("ChatRouter", "Re-sent payment details for offer $offerId (sweep)")
        }
    }

    private suspend fun sendPaymentDetails(
        peerId: String,
        offerId: String,
        details: Map<String, com.neop2p.domain.model.PaymentDetails>,
        onSent: () -> Unit
    ) {
        val payload = paymentDetailsPayload(details)
        android.util.Log.d("ChatRouter", "Auto-share payload for $offerId: ${payload.length} bytes, methods=${details.keys}, nonEmpty=${details.values.count { it.accountNumber.isNotBlank() }}/=${details.size}")
        signal.encrypt(peerId, payload.toByteArray(Charsets.UTF_8))
            .onSuccess { ct ->
                val msg = AppMessage.Chat(peerId, offerId, ct)
                queue.send(peerId, msg)
                queue.drainFor(peerId) { pending ->
                    val env = EnvelopeCodec.encode(pending)
                    val rnsOk = rnsTransport.send(peerId, env.data, env.type).isSuccess
                    if (rnsOk) return@drainFor true
                    transport.send(peerId, env.data, env.type).isSuccess
                }
                onSent()
            }
            .onFailure {
                android.util.Log.w("ChatRouter", "Auto-share payment details skipped (will retry): ${it.message}")
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
            android.util.Log.i("ChatRouter", "Inbound payment details for $offerId: payload=${plain.length} bytes, methods=${parsed.keys}, nonEmpty=${parsed.values.count { it.accountNumber.isNotBlank() }}/=${parsed.size}")
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
    ): Result<Boolean> = sendText(peerId, offerId, payload.toJson().toByteArray(Charsets.UTF_8))

    /**
     * Send a structured payment-receipt REJECTION to the peer over E2EE chat.
     * The escrow status does NOT change (release gate untouched — only
     * confirmReceipt releases); the rejection is advisory evidence in the
     * thread so the buyer can see WHY and resubmit or dispute.
     */
    suspend fun sendRejectMessage(
        offerId: String,
        peerId: String,
        payload: PaymentReceiptRejectPayload
    ): Result<Boolean> = sendText(peerId, offerId, payload.toJson().toByteArray(Charsets.UTF_8))
}

/**
 * Structured E2EE payment-receipt rejection: which reference was rejected and
 * a machine reason code (JUMLAH_SALAH / NAMA_BEDA / BELUM_MASUK / LAINNYA).
 * The escrow status is NOT changed by this message — it is evidence in the
 * trade thread and an instruction to the buyer (fix + resubmit, or dispute).
 */
data class PaymentReceiptRejectPayload(
    val reference: String,
    val reason: String,
    val note: String = ""
) {
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"payment_receipt_reject\",")
        sb.append("\"reference\":\"").append(reference).append("\",")
        sb.append("\"reason\":\"").append(reason).append("\"")
        if (note.isNotBlank()) {
            sb.append(",\"note\":\"").append(note.replace("\"", "'")).append("\"")
        }
        sb.append("}")
        return sb.toString()
    }
}

fun parsePaymentReceiptRejectPayload(json: String): PaymentReceiptRejectPayload? {
    return try {
        val obj = org.json.JSONObject(json)
        if (obj.optString("type") != "payment_receipt_reject") return null
        PaymentReceiptRejectPayload(
            reference = obj.getString("reference"),
            reason = obj.getString("reason"),
            note = obj.optString("note")
        )
    } catch (e: Exception) {
        null
    }
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
