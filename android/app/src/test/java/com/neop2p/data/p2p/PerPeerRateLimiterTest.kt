package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerPeerRateLimiterTest {

    @Test
    fun `burst of 20 passes then the 21st is dropped`() {
        val limiter = PerPeerRateLimiter(maxBurst = 20, refillPerSecond = 1.0)
        val t0 = 1_000_000L
        for (i in 1..20) {
            assertTrue("message $i must pass", limiter.tryAcquire("peerA", t0 + i))
        }
        assertFalse("21st message in the same second must be dropped", limiter.tryAcquire("peerA", t0 + 21))
    }

    @Test
    fun `tokens refill over time`() {
        val limiter = PerPeerRateLimiter(maxBurst = 10, refillPerSecond = 2.0)
        val t0 = 1_000_000L
        repeat(10) { limiter.tryAcquire("peerA", t0 + it) }
        assertFalse(limiter.tryAcquire("peerA", t0 + 10))
        // 2 tokens/sec → after 1s, 2 more messages pass.
        assertTrue(limiter.tryAcquire("peerA", t0 + 1_000))
        assertTrue(limiter.tryAcquire("peerA", t0 + 1_000))
        assertFalse(limiter.tryAcquire("peerA", t0 + 1_000))
    }

    @Test
    fun `peers are isolated`() {
        val limiter = PerPeerRateLimiter(maxBurst = 5, refillPerSecond = 1.0)
        val t0 = 1_000_000L
        repeat(5) { limiter.tryAcquire("peerA", t0 + it) }
        assertFalse(limiter.tryAcquire("peerA", t0 + 5))
        assertTrue("peerB must be unaffected by peerA's exhaustion", limiter.tryAcquire("peerB", t0 + 5))
    }

    @Test
    fun `blank peer id is always rejected`() {
        val limiter = PerPeerRateLimiter()
        assertFalse(limiter.tryAcquire("", 1_000L))
        assertFalse(limiter.tryAcquire("   ", 1_000L))
    }

    @Test
    fun `evictIdle drops stale buckets past the cap`() {
        val l = PerPeerRateLimiter(maxPeers = 2)
        val now = 1_000_000L
        l.tryAcquire("a", now); l.tryAcquire("b", now)
        l.tryAcquire("c", now + 1)                       // map now > maxPeers
        l.evictIdle(now + 10 * 60_000L, idleMs = 60_000L)
        // all three are stale by then; a fresh acquire starts with a full burst
        var allowed = 0
        repeat(25) { if (l.tryAcquire("d", now + 10 * 60_000L)) allowed++ }
        assertEquals(20, allowed)                        // full bucket: maxBurst
    }
}
