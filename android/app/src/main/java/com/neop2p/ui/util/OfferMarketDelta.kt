package com.neop2p.ui.util

/**
 * Offer price versus the market, in tenths of a percent (so the value stays an
 * integer: money math never round-trips through Double).
 *
 * `1_024` against a market of `1_000` is +2.4%, reported as `24`. Null when there is
 * no market reference — showing "+0.0%" against an unknown market would be a lie.
 */
fun marketDeltaTenths(offerPrice: Long, marketPrice: Long): Int? {
    if (marketPrice <= 0L) return null
    return (((offerPrice - marketPrice) * 1000) / marketPrice).toInt()
}

/** `"+2.4%"` / `"−1.1%"` / `"0.0%"` — one decimal, typographic minus. */
fun marketDeltaLabel(deltaTenths: Int): String {
    val sign = when {
        deltaTenths > 0 -> "+"
        deltaTenths < 0 -> "\u2212"
        else -> ""
    }
    val magnitude = kotlin.math.abs(deltaTenths)
    return "$sign${magnitude / 10}.${magnitude % 10}%"
}
