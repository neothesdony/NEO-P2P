package com.neop2p.ui.screens.home

import com.neop2p.ui.util.marketDeltaLabel
import com.neop2p.ui.util.marketDeltaTenths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfferMarketDeltaTest {

    @Test
    fun premium_is_positive() {
        assertEquals(24, marketDeltaTenths(1_024, 1_000))
    }

    @Test
    fun discount_is_negative() {
        assertEquals(-11, marketDeltaTenths(989, 1_000))
    }

    @Test
    fun no_market_is_null() {
        assertNull(marketDeltaTenths(1_000, 0))
    }

    @Test
    fun a_negative_market_is_null() {
        assertNull(marketDeltaTenths(1_000, -5))
    }

    @Test
    fun label_has_one_decimal() {
        assertEquals("+2.4%", marketDeltaLabel(24))
        assertEquals("\u22121.1%", marketDeltaLabel(-11))
        assertEquals("0.0%", marketDeltaLabel(0))
    }

    @Test
    fun a_large_premium_stays_readable() {
        assertEquals("+125.0%", marketDeltaLabel(1_250))
    }
}
