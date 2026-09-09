package com.neop2p.data.p2p

/**
 * 2026-09-10: pacing normalized to production LXMF norms.
 * Sideband (reference client) auto-announces on a randomized 90-300 MINUTE
 * cadence; LXMF-kt's example node announces every 60s. Announces are an
 * ACCELERATOR for queued delivery (LXMRouter.handleDeliveryAnnounce flushes
 * pending outbound), never the delivery mechanism — the router retries
 * (5 attempts / 10s) + path requests + propagation fallback deliver.
 * One offer digest per tick = 1 announce/30s per destination at the 30s
 * tick — 16x under the fork's MAX_RATE_TIMESTAMPS=16/30s per-dest cap,
 * so 6+ devices sharing one destination hash fit with 10x headroom.
 */
object AnnouncePacing {
    fun offerTickMs(idle: Boolean): Long = if (idle) 60_000L else 30_000L

    fun tombstoneEveryNTicks(): Int = 4

    fun announcesPer30s(tickMs: Long): Long = (30_000L / tickMs).coerceAtLeast(1L)
}
