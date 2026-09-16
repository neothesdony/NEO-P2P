package com.neop2p.ui.screens.chathistory

import com.neop2p.data.local.ChatUnreadPolicy
import com.neop2p.data.local.entity.ChatMessageEntity
import com.neop2p.data.local.entity.TradeOfferEntity

/**
 * One conversation in the Home "Chats" list: the other party, the last
 * activity time, the unread count, and the originating offer's status.
 */
data class ChatThread(
    val offerId: String,
    val peerId: String,
    val lastMessageAt: Long,
    val unread: Int,
    val status: String
)

/**
 * Build the conversation list from persisted chat rows and trade offers.
 *
 * Pure and Android-free so it is unit-testable without Room. Covers EVERY
 * offer with messages — including finished trades — because a terminal trade
 * has no other UI path to its chat (Trades opens the escrow detail, not the
 * chat). Threads whose offer row is gone or whose counterparty cannot be
 * resolved are dropped: `ChatScreen` needs a peerId to route.
 *
 * [myPeerId] blank (identity locked behind device auth) keeps the threads but
 * cannot tell creator from taker, so the creator's peer id is used as the
 * counterparty — the same fail-visible behaviour as [ChatUnreadPolicy].
 */
fun chatThreads(
    messages: List<ChatMessageEntity>,
    offers: List<TradeOfferEntity>,
    myPeerId: String
): List<ChatThread> {
    val offersByOfferId = offers.associateBy { it.offer_id }
    return messages
        .groupBy { it.offer_id }
        .mapNotNull { (offerId, rows) ->
            val offer = offersByOfferId[offerId] ?: return@mapNotNull null
            val peer = if (myPeerId.isNotBlank() && offer.creator_peer_id == myPeerId) {
                offer.matched_peer_id
            } else {
                offer.creator_peer_id
            }
            if (peer.isNullOrBlank() || peer == myPeerId) return@mapNotNull null
            ChatThread(
                offerId = offerId,
                peerId = peer,
                lastMessageAt = rows.maxOf { it.sent_at },
                unread = ChatUnreadPolicy.unreadForOffer(rows, offerId, myPeerId),
                status = offer.status
            )
        }
        .sortedByDescending { it.lastMessageAt }
}
