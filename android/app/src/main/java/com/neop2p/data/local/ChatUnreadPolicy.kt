package com.neop2p.data.local

import com.neop2p.data.local.entity.ChatMessageEntity

/**
 * What the Home banner counts as "unread": a persisted chat row the user has
 * not seen yet.
 *
 * Two rules, both load-bearing:
 *  - only inbound rows count — our own outgoing rows are never unread, even
 *    when an older build persisted them with the read flag clear
 *    (`ChatRouter.sendFile` did exactly that before 2026-09-16);
 *  - a blank [myPeerId] (identity locked behind device auth) must not hide a
 *    message: we cannot prove ownership, so the row still counts.
 *
 * Pure and Android-free so it is unit-testable without Room or Robolectric.
 */
object ChatUnreadPolicy {

    /** True when [row] is a message the local user has not seen. */
    fun countsAsUnread(row: ChatMessageEntity, myPeerId: String): Boolean =
        !row.is_read && (myPeerId.isBlank() || row.sender_peer_id != myPeerId)

    /** Unread total across [rows] (callers pass only the offers they count). */
    fun unreadTotal(rows: List<ChatMessageEntity>, myPeerId: String): Int =
        rows.count { countsAsUnread(it, myPeerId) }

    /** Unread count for one offer's thread — drives the top-bar chat badge. */
    fun unreadForOffer(rows: List<ChatMessageEntity>, offerId: String, myPeerId: String): Int =
        rows.count { it.offer_id == offerId && countsAsUnread(it, myPeerId) }
}
