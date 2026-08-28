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
    val btc = trimmed.toDoubleOrNull() ?: return null
    if (btc <= 0.0) return null
    return (btc * 100_000_000.0).toLong()
}
