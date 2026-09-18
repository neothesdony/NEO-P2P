package com.neop2p.data.network.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitcoinerLiveProviderTest {

    @Test
    fun `estimates are taken ascending by target minutes`() {
        val json = """
            {"timestamp":1,"estimates":{
              "30":{"sat_per_vbyte":4.0},
              "60":{"sat_per_vbyte":3.0},
              "120":{"sat_per_vbyte":2.0},
              "1440":{"sat_per_vbyte":1.0}}}
        """.trimIndent()
        val e = BitcoinerLiveProvider.parseFeeEstimate(json)!!
        assertEquals(4L, e.fastest)
        assertEquals(3L, e.halfHour)
        assertEquals(2L, e.hour)
    }

    @Test
    fun `single estimate is reused for all tiers`() {
        val json = """{"estimates":{"60":{"sat_per_vbyte":7.0}}}"""
        val e = BitcoinerLiveProvider.parseFeeEstimate(json)!!
        assertEquals(7L, e.fastest)
        assertEquals(7L, e.halfHour)
        assertEquals(7L, e.hour)
    }

    @Test
    fun `junk and empty estimates are null`() {
        assertNull(BitcoinerLiveProvider.parseFeeEstimate("not json"))
        assertNull(BitcoinerLiveProvider.parseFeeEstimate("""{"estimates":{}}"""))
    }
}
