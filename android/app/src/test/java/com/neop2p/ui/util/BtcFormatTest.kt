package com.neop2p.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BtcFormatTest {

    @Test
    fun `formats whole btc without scientific notation`() {
        assertEquals("1.0000", formatBtc(100_000_000L))
        assertEquals("0.001000", formatBtc(100_000L))
        assertEquals("0.00001000", formatBtc(1_000L))
    }

    @Test
    fun `parses btc input to sats`() {
        assertEquals(100_000_000L, parseBtcToSats("1"))
        assertEquals(125_000L, parseBtcToSats("0.00125"))
        assertEquals(1L, parseBtcToSats("0.00000001"))
        assertEquals(100_000L, parseBtcToSats(" 0.001 "))
    }

    @Test
    fun `rejects blank garbage and non-positive input`() {
        assertNull(parseBtcToSats(""))
        assertNull(parseBtcToSats("   "))
        assertNull(parseBtcToSats("abc"))
        assertNull(parseBtcToSats("0"))
        assertNull(parseBtcToSats("-0.001"))
    }

    @Test
    fun `sub-satoshi precision truncates never rounds up`() {
        // 0.000000015 BTC = 1.5 sats → truncates to 1, never rounds to 2.
        assertEquals(1L, parseBtcToSats("0.000000015"))
    }
}
