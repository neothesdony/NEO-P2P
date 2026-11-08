package com.neop2p.ui.screens.home

import com.neop2p.ui.util.formatBtc

/**
 * One cell of the bento header. [accent] marks the number that matters most in the
 * current state (unread first, then locked funds, then open trades).
 */
data class BentoStat(val label: String, val value: String, val accent: Boolean)

/**
 * Home's three numbers, in priority order.
 *
 * Zero-valued cells are dropped so the header collapses instead of showing "0 open
 * trades / 0 sats / 0 unread", and an all-zero portfolio returns an empty list — the
 * caller then shows the empty state's own header rather than a row of zeros.
 *
 * Labels are resolved by the caller (they are string resources); this function only
 * decides *which* numbers deserve a cell.
 */
fun bentoStats(portfolio: PortfolioHeader): List<BentoStat> {
    if (portfolio.openTrades == 0 && portfolio.lockedSats == 0L && portfolio.unreadTotal == 0) {
        return emptyList()
    }
    return buildList {
        if (portfolio.unreadTotal > 0) {
            add(BentoStat("unread", portfolio.unreadTotal.toString(), accent = true))
        }
        if (portfolio.lockedSats > 0L) {
            add(BentoStat("locked", formatBtc(portfolio.lockedSats), accent = false))
        }
        if (portfolio.openTrades > 0) {
            add(BentoStat("open", portfolio.openTrades.toString(), accent = false))
        }
    }.take(3)
}
