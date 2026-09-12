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

/** Total bitcoin supply in satoshis — the hard ceiling for any parsed amount. */
private const val MAX_BTC_SATS = 21_000_000L * 100_000_000L

/**
 * Parse a user-entered BTC amount (e.g. "0.00125") into satoshis.
 * Returns null for blank/garbage/negative input. Sub-satoshi precision is
 * truncated (never rounded up — a user can't accidentally send more than
 * they typed). Money stays integer: the result is a whole number of sats.
 *
 * Audit P3-3 (2026-09-12): a comma is accepted as the decimal separator (an
 * Indonesian keyboard emits one), while scientific notation, signs and
 * thousands separators are rejected outright. The value is bounded by the
 * money supply BEFORE narrowing, because `BigDecimal.toLong()` silently
 * returns the low-order 64 bits — an unbounded parse could turn "1e30" into a
 * small positive amount that passes the balance check.
 */
fun parseBtcToSats(input: String): Long? {
    val normalized = input.trim().replace(',', '.')
    if (normalized.isEmpty()) return null
    // Digits with at most one dot. No sign, no exponent, no grouping.
    if (!normalized.matches(Regex("^[0-9]*\\.?[0-9]+$"))) return null
    val btc = try {
        java.math.BigDecimal(normalized)
    } catch (e: NumberFormatException) {
        return null
    }
    if (btc <= java.math.BigDecimal.ZERO) return null
    // Exact decimal math: 0.29 → 29_000_000, never 28_999_999. setScale DOWN
    // truncates toward zero, so sub-satoshi input still never rounds up.
    val sats = btc.multiply(java.math.BigDecimal(100_000_000))
        .setScale(0, java.math.RoundingMode.DOWN)
    if (sats > java.math.BigDecimal.valueOf(MAX_BTC_SATS)) return null
    return sats.toLong()
}
