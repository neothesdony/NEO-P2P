package com.neop2p.data.p2p

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-peer token-bucket rate limiter for INBOUND messages (H4, 2026-09-11).
 *
 * A hostile peer can otherwise flood chat / offer_request / offer_status
 * messages and grow the DB without limit. Each peerId gets its own bucket;
 * peers are isolated (one peer exhausting its bucket never affects another).
 * The map is LRU-evicted at [maxPeers] to bound memory.
 *
 * Pure and JVM-testable.
 */
class PerPeerRateLimiter(
    private val maxBurst: Int = 20,
    private val refillPerSecond: Double = 1.0,
    private val maxPeers: Int = 256,
) {
    private class Bucket {
        var tokens: Double = 0.0
        var lastRefillMs: Long = 0L
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()

    @Synchronized
    fun tryAcquire(peerId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val id = peerId.trim()
        if (id.isBlank()) return false
        val bucket = buckets.computeIfAbsent(id) { Bucket() }
        if (bucket.lastRefillMs == 0L) {
            bucket.tokens = maxBurst.toDouble()
            bucket.lastRefillMs = nowMs
        } else {
            val elapsed = (nowMs - bucket.lastRefillMs).coerceAtLeast(0L)
            bucket.tokens = (bucket.tokens + elapsed / 1000.0 * refillPerSecond)
                .coerceAtMost(maxBurst.toDouble())
            bucket.lastRefillMs = nowMs
        }
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            return true
        }
        return false
    }

    /** Evict idle peers so a flood of new peerIds cannot grow the map forever. */
    fun evictIdle(nowMs: Long = System.currentTimeMillis(), idleMs: Long = 60_000L) {
        if (buckets.size <= maxPeers) return
        val cutoff = nowMs - idleMs
        buckets.entries.removeIf { (_, b) -> b.lastRefillMs < cutoff }
    }
}
