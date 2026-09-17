package com.neop2p.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepThrottleStoreTest {

    @Test
    fun `timestamp map round-trips`() {
        val map = mapOf("escrow_1:refund_grace_reminder" to 1_700_000_000_000L, "escrow_2:payment_grace_reminder" to 42L)
        assertEquals(map, SweepThrottleStore.parse(SweepThrottleStore.toJson(map)))
    }

    @Test
    fun `absent and corrupt blobs parse to empty`() {
        assertTrue(SweepThrottleStore.parse(null).isEmpty())
        assertTrue(SweepThrottleStore.parse("").isEmpty())
        assertTrue(SweepThrottleStore.parse("[not json").isEmpty())
    }

    @Test
    fun `unknown keys survive a round-trip`() {
        val map = mapOf("future:key" to 123L)
        assertEquals(map, SweepThrottleStore.parse(SweepThrottleStore.toJson(map)))
    }
}
