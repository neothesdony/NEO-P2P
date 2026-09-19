package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepThrottleTest {

    @Test
    fun `first emit is allowed`() {
        assertTrue(SweepThrottle.shouldEmit(lastEmittedAtMs = null, nowMs = 1_000L))
    }

    @Test
    fun `re-send inside the window is suppressed`() {
        assertFalse(SweepThrottle.shouldEmit(lastEmittedAtMs = 1_000L, nowMs = 1_000L + 4 * 60_000L))
    }

    @Test
    fun `emit after the window is allowed`() {
        assertTrue(SweepThrottle.shouldEmit(lastEmittedAtMs = 1_000L, nowMs = 1_000L + 5 * 60_000L))
    }

    @Test
    fun `custom window is honoured`() {
        assertFalse(SweepThrottle.shouldEmit(0L, 999L, windowMs = 1_000L))
        assertTrue(SweepThrottle.shouldEmit(0L, 1_000L, windowMs = 1_000L))
    }

    @Test
    fun `terminal status stops polling`() {
        val terminal = setOf("RELEASED", "REFUNDED", "CANCELLED")
        assertTrue(SweepThrottle.isTerminal("RELEASED", terminal))
        assertTrue(SweepThrottle.isTerminal("CANCELLED", terminal))
        assertFalse(SweepThrottle.isTerminal("FUNDED", terminal))
    }
}
