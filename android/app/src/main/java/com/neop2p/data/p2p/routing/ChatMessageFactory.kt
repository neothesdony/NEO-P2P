package com.neop2p.data.p2p.routing

import com.neop2p.data.local.entity.ChatMessageEntity

/**
 * The two shapes of a `chat_messages` row, in one place so the read flag
 * cannot drift per call site.
 *
 * 2026-09-16: the outgoing file placeholder was written inline with
 * `is_read = false` and `sender_peer_id = peer`, so a file the user SENT
 * counted as their own unread message in the Home banner. Both directions now
 * go through here, and [outboundFile] is read on arrival.
 *
 * Note: [outboundFile] keeps `sender_peer_id = peerId` — that is the existing
 * bubble-attribution behaviour of the file placeholder and is out of scope
 * here; only the read flag is corrected.
 */
internal object ChatMessageFactory {

    /** Inbound ciphertext from the peer: unread until the user opens the chat. */
    fun inbound(
        messageId: String,
        offerId: String,
        fromPeerId: String,
        ciphertext: ByteArray,
        sentAt: Long
    ) = ChatMessageEntity(
        message_id = messageId,
        offer_id = offerId,
        sender_peer_id = fromPeerId,
        ciphertext = ciphertext,
        is_read = false,
        sent_at = sentAt
    )

    /**
     * A file WE sent: our own row, so it is already read.
     *
     * [fileAttachment] stays nullable for callers that only need the
     * placeholder shape, but `ChatRouter.sendFile` always passes the bytes so
     * history reloads as a file bubble instead of
     * "[encrypted — session unavailable]".
     */
    fun outboundFile(
        messageId: String,
        offerId: String,
        peerId: String,
        sentAt: Long,
        fileAttachment: ByteArray? = null
    ) = ChatMessageEntity(
        message_id = messageId,
        offer_id = offerId,
        sender_peer_id = peerId,
        ciphertext = ByteArray(0),
        is_read = true,
        sent_at = sentAt,
        file_attachment = fileAttachment
    )

    /** Bubble label for a file row (both directions). */
    fun fileLabel(fileName: String, sizeBytes: Int): String =
        "[File: $fileName, $sizeBytes bytes]"
}
