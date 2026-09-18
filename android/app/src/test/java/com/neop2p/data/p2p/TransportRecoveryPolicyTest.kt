package com.neop2p.data.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportRecoveryPolicyTest {

    private fun check(
        running: Boolean = true,
        online: Int = 0,
        expected: Int = 2,
        readySince: Long = 0L,
        now: Long = TransportRecoveryPolicy.GRACE_MS + 1,
        last: Long = 0L,
    ) = TransportRecoveryPolicy.shouldRecover(running, online, expected, readySince, now, last)

    @Test
    fun `recovers when running with zero interfaces past grace`() {
        assertTrue(check())
    }

    @Test
    fun `does not recover while not running`() {
        assertFalse(check(running = false))
    }

    @Test
    fun `does not recover when no interfaces are expected`() {
        assertFalse(check(expected = 0))
    }

    @Test
    fun `does not recover before grace`() {
        assertFalse(check(now = TransportRecoveryPolicy.GRACE_MS - 1))
    }

    @Test
    fun `does not recover outside the window`() {
        assertFalse(check(now = TransportRecoveryPolicy.WINDOW_MS + 1))
    }

    @Test
    fun `does not recover inside cooldown`() {
        assertFalse(check(last = TransportRecoveryPolicy.GRACE_MS + 1 - 1))
    }

    @Test
    fun `does not recover when at least one interface is online`() {
        assertFalse(check(online = 1))
    }
}
