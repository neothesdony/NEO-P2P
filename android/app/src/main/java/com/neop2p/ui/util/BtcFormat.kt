package com.neop2p.ui.util

import java.util.Locale

/**
 * Format a satoshi amount as a human-readable BTC string WITHOUT scientific
 * notation. `(sats / 100_000_000.0).toString()` yields "1.4999E-4" for small
 * amounts — every screen that used it showed broken amounts on dust trades.
 *
 * Precision ladder (matches HistoryScreen's original):
 *  - >= 1 BTC        → 4 decimals
 *  - >= 0.001 BTC    → 6 decimals
 *  - else (dust)     → 8 decimals
 */
fun formatBtc(sats: Long): String {
    val btc = sats / 100_000_000.0
    return if (btc >= 1) String.format(Locale.US, "%.4f", btc)
    else if (btc >= 0.001) String.format(Locale.US, "%.6f", btc)
    else String.format(Locale.US, "%.8f", btc)
}

/**
 * Parse a user-entered BTC amount (e.g. "0.00125") into satoshis.
 * Returns null for blank/garbage/negative input. Sub-satoshi precision is
 * truncated (never rounded up — a user can't accidentally send more than
 * they typed). Money stays integer: the result is a whole number of sats.
 */
fun parseBtcToSats(input: String): Long? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val btc = try {
        java.math.BigDecimal(trimmed)
    } catch (e: NumberFormatException) {
        return null
    }
    if (btc <= java.math.BigDecimal.ZERO) return null
    // Exact decimal math: 0.29 → 29_000_000, never 28_999_999. toLong()
    // truncates toward zero, so sub-satoshi input still never rounds up.
    return btc.multiply(java.math.BigDecimal(100_000_000)).toLong()
}
