package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeFailoverPolicyTest {

    private val policy = NodeFailoverPolicy(maxAttempts = 3, cooldownMs = 1_000L)

    @Test
    fun `fresh state may attempt`() {
        assertTrue(policy.canAttempt(NodeFailoverPolicy.State(), nowMs = 0L))
    }

    @Test
    fun `cooldown blocks attempts until it elapses`() {
        val state = policy.onFailure(policy.onFailure(policy.onFailure(NodeFailoverPolicy.State(), 0L), 0L), 0L)
        assertFalse(policy.canAttempt(state, nowMs = 500L))
        assertTrue(policy.canAttempt(state, nowMs = 1_000L))
    }

    @Test
    fun `counters reset on success`() {
        val failed = policy.onFailure(NodeFailoverPolicy.State(), 0L)
        assertEquals(1, failed.consecutiveFailures)
        assertEquals(NodeFailoverPolicy.State(), policy.onSuccess(failed))
    }

    @Test
    fun `exhaustion enters cooldown and resets the counter not a permanent latch`() {
        var state = NodeFailoverPolicy.State()
        repeat(3) { state = policy.onFailure(state, 0L) }
        assertEquals(0, state.consecutiveFailures)
        assertEquals(1_000L, state.cooldownUntilMs)
        // After the cooldown a new attempt is allowed.
        assertTrue(policy.canAttempt(state, 1_000L))
    }

    @Test
    fun `partial failures track the count`() {
        val s1 = policy.onFailure(NodeFailoverPolicy.State(), 0L)
        val s2 = policy.onFailure(s1, 0L)
        assertEquals(2, s2.consecutiveFailures)
        assertEquals(0L, s2.cooldownUntilMs)
    }
}
