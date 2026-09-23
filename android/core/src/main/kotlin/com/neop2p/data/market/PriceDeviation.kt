package com.neop2p.data.market

import kotlin.math.abs

/**
 * Advisory price-deviation check (Phase 3, 2026-09-23): compares an offer's
 * whole-rupiah price to the live market price. Integer-only (never touches the
 * money formulas) and non-blocking. 1000 bps = 10%.
 */
object PriceDeviation {
    const val THRESHOLD_BPS: Long = 1_000L

    fun bps(priceIdr: Long, marketIdr: Long): Long {
        if (priceIdr <= 0L || marketIdr <= 0L) return 0L
        return abs(priceIdr - marketIdr) * 10_000L / marketIdr
    }

    fun isDeviant(
        priceIdr: Long,
        marketIdr: Long,
        thresholdBps: Long = THRESHOLD_BPS
    ): Boolean = bps(priceIdr, marketIdr) > thresholdBps
}
