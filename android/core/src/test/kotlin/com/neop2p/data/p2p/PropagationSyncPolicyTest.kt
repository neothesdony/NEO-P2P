package com.neop2p.data.p2p

import network.reticulum.lxmf.LXMRouter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropagationSyncPolicyTest {

    @Test
    fun `never synced and idle is due`() {
        assertTrue(
            PropagationSyncPolicy.shouldSync(
                lastSyncAtMs = 0L,
                nowMs = 1_000_000L,
                idle = false,
                state = LXMRouter.PropagationTransferState.IDLE,
            )
        )
    }

    @Test
    fun `skips while a transfer is in flight`() {
        assertTrue(PropagationSyncPolicy.isBusy(LXMRouter.PropagationTransferState.LINK_ESTABLISHING))
        assertTrue(PropagationSyncPolicy.isBusy(LXMRouter.PropagationTransferState.RECEIVING_MESSAGES))
        assertFalse(PropagationSyncPolicy.isBusy(LXMRouter.PropagationTransferState.COMPLETE))
        assertFalse(PropagationSyncPolicy.isBusy(LXMRouter.PropagationTransferState.FAILED))
    }

    @Test
    fun `respects the foreground interval`() {
        val interval = PropagationSyncPolicy.FOREGROUND_INTERVAL_MS
        assertFalse(
            PropagationSyncPolicy.shouldSync(
                lastSyncAtMs = 1_000_000L,
                nowMs = 1_000_000L + interval - 1,
                idle = false,
                state = LXMRouter.PropagationTransferState.COMPLETE,
            )
        )
        assertTrue(
            PropagationSyncPolicy.shouldSync(
                lastSyncAtMs = 1_000_000L,
                nowMs = 1_000_000L + interval,
                idle = false,
                state = LXMRouter.PropagationTransferState.COMPLETE,
            )
        )
    }

    @Test
    fun `background uses the longer interval`() {
        assertTrue(PropagationSyncPolicy.intervalMs(idle = true) > PropagationSyncPolicy.intervalMs(idle = false))
    }

    @Test
    fun `busy state suppresses a due sync`() {
        assertFalse(
            PropagationSyncPolicy.shouldSync(
                lastSyncAtMs = 0L,
                nowMs = 99_000_000L,
                idle = false,
                state = LXMRouter.PropagationTransferState.LISTING_MESSAGES,
            )
        )
    }
}
