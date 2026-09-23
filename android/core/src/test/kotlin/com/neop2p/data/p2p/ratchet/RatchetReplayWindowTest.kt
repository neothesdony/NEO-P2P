package com.neop2p.data.p2p.ratchet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RatchetReplayWindowTest {

    @Test
    fun `in-order message is accepted`() {
        assertEquals(
            RatchetReplayWindow.Decision.InOrder(5),
            RatchetReplayWindow.decide(recvCount = 5, msgNum = 5, hasSkippedKey = false, skippedSize = 0)
        )
    }

    @Test
    fun `ahead message is accepted as skip-ahead`() {
        assertEquals(
            RatchetReplayWindow.Decision.SkipAhead(msgNum = 8, skipFrom = 5),
            RatchetReplayWindow.decide(recvCount = 5, msgNum = 8, hasSkippedKey = false, skippedSize = 0)
        )
    }

    @Test
    fun `stale message without a skipped key is rejected`() {
        val d = RatchetReplayWindow.decide(recvCount = 9, msgNum = 4, hasSkippedKey = false, skippedSize = 0)
        assertTrue(d is RatchetReplayWindow.Decision.Reject)
    }

    @Test
    fun `stale message with a stored skipped key is accepted`() {
        assertEquals(
            RatchetReplayWindow.Decision.FromSkipped(4),
            RatchetReplayWindow.decide(recvCount = 9, msgNum = 4, hasSkippedKey = true, skippedSize = 1)
        )
    }

    @Test
    fun `message beyond the per-chain skip cap is rejected`() {
        val d = RatchetReplayWindow.decide(
            recvCount = 0, msgNum = RatchetState.MAX_SKIP_PER_CHAIN + 1,
            hasSkippedKey = false, skippedSize = 0
        )
        assertTrue(d is RatchetReplayWindow.Decision.Reject)
    }

    @Test
    fun `skip-ahead that would overflow the skipped store is rejected`() {
        val d = RatchetReplayWindow.decide(
            recvCount = 0, msgNum = 100, hasSkippedKey = false,
            skippedSize = RatchetState.MAX_SKIPPED - 50
        )
        assertTrue(d is RatchetReplayWindow.Decision.Reject)
    }

    @Test
    fun `negative message number is rejected`() {
        assertTrue(
            RatchetReplayWindow.decide(0, -1, hasSkippedKey = false, skippedSize = 0)
                is RatchetReplayWindow.Decision.Reject
        )
    }
}
