package com.neop2p.data.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceDeviationTest {
    @Test
    fun equalPrices_areNotDeviant() {
        assertFalse(PriceDeviation.isDeviant(1_000_000_000L, 1_000_000_000L))
    }

    @Test
    fun tenPercentAbove_isAtThreshold_notDeviant() {
        // exactly 1000 bps is not > threshold
        assertFalse(PriceDeviation.isDeviant(1_100_000_000L, 1_000_000_000L))
    }

    @Test
    fun tenPercentBelow_isAtThreshold_notDeviant() {
        assertFalse(PriceDeviation.isDeviant(900_000_000L, 1_000_000_000L))
    }

    @Test
    fun elevenPercentAbove_isDeviant() {
        assertTrue(PriceDeviation.isDeviant(1_110_000_000L, 1_000_000_000L))
    }

    @Test
    fun bpsIsInteger() {
        assertEquals(500L, PriceDeviation.bps(1_050_000_000L, 1_000_000_000L))
    }

    @Test
    fun zeroOrNegativeInputs_areNotDeviant() {
        assertFalse(PriceDeviation.isDeviant(0L, 1_000_000_000L))
        assertFalse(PriceDeviation.isDeviant(1_000_000_000L, 0L))
    }
}
