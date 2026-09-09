package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnouncePacingTest {
    @Test
    fun `foreground offer tick is 30s`() {
        assertEquals(30_000L, AnnouncePacing.offerTickMs(idle = false))
    }

    @Test
    fun `idle offer tick stays 60s`() {
        assertEquals(60_000L, AnnouncePacing.offerTickMs(idle = true))
    }

    @Test
    fun `per-30s announce count at 30s tick fits cap with headroom`() {
        // 30s tick = 1 announce/30s per offer; cap is 16/30s per dest
        assertEquals(1L, AnnouncePacing.announcesPer30s(30_000L))
    }

    @Test
    fun `tombstone cadence remains one per 4 live ticks`() {
        assertEquals(4, AnnouncePacing.tombstoneEveryNTicks())
    }
}
