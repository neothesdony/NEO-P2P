package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sticky explorer-base ordering: the last base that worked is tried first so a
 * network where the configured primary is blocked (e.g. an ISP MITM on
 * mempool.space) does not pay the dead base's connection-reset penalty on
 * every call.
 */
class ChainMonitorBasesTest {

    private val bases = listOf("https://a/api", "https://b/api", "https://c/api")

    @Test
    fun preferredBaseGoesFirst() {
        assertEquals(
            listOf("https://b/api", "https://a/api", "https://c/api"),
            ChainMonitor.orderedBases(bases, "https://b/api")
        )
    }

    @Test
    fun preferredAlreadyFirstLeavesOrderUnchanged() {
        assertEquals(bases, ChainMonitor.orderedBases(bases, "https://a/api"))
    }

    @Test
    fun unknownPreferredLeavesOrderUnchanged() {
        assertEquals(bases, ChainMonitor.orderedBases(bases, "https://z/api"))
    }

    @Test
    fun nullPreferredLeavesOrderUnchanged() {
        assertEquals(bases, ChainMonitor.orderedBases(bases, null))
    }

    @Test
    fun reorderingNeverDropsOrDuplicatesABase() {
        val out = ChainMonitor.orderedBases(bases, "https://c/api")
        assertEquals(bases.size, out.size)
        assertEquals(bases.toSet(), out.toSet())
        assertEquals(bases.size, out.toSet().size)
    }
}
