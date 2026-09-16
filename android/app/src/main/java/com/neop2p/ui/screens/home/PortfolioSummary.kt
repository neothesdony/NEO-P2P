package com.neop2p.ui.screens.home

import com.neop2p.data.local.ChatUnreadPolicy
import com.neop2p.data.local.entity.ChatMessageEntity
import com.neop2p.data.local.entity.EscrowEntity

/**
 * The Home portfolio header: open-trade count, sats locked in the user's own
 * escrows, and the unread chat total.
 *
 * Moved out of HomeViewModel (2026-09-16) so the composition is a pure
 * function the unit tests drive — the ViewModel only wires flows into it.
 */
data class PortfolioHeader(
    val openTrades: Int = 0,
    val lockedSats: Long = 0L,
    val unreadTotal: Int = 0
)

/** Escrow statuses that leave the marketplace feed: not open trades. */
private val TERMINAL_ESCROW_STATUSES = setOf("RELEASED", "REFUNDED", "CANCELLED")

/**
 * Compose the header.
 *
 * [messages] is the whole chat table: callers pass the full list so the value
 * recomputes on ANY chat write (insert or read-flag update). Unread rows whose
 * offer has no escrow yet are deliberately not counted — the header reports
 * trade activity, and the top-bar chat badge covers the pre-escrow case.
 */
fun portfolioSummary(
    escrows: List<EscrowEntity>,
    messages: List<ChatMessageEntity>,
    myPeerId: String
): PortfolioHeader {
    val openEscrows = escrows.filter { it.status !in TERMINAL_ESCROW_STATUSES }
    val escrowOfferIds = escrows.map { it.offer_id }.toSet()
    return PortfolioHeader(
        openTrades = openEscrows.size,
        lockedSats = openEscrows
            .filter { it.seller_peer_id == myPeerId }
            .sumOf { it.deposit_amount_sats },
        unreadTotal = ChatUnreadPolicy.unreadTotal(
            messages.filter { it.offer_id in escrowOfferIds },
            myPeerId
        )
    )
}
