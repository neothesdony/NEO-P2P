package com.neop2p.data.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketPriceParseTest {

    @Test
    fun `parses a valid coingecko response`() {
        val json = """{"bitcoin":{"idr":1500000000.5}}"""
        assertEquals(1_500_000_000L, MarketPriceService.parsePrice(json))
    }

    @Test
    fun `null on malformed json`() {
        assertNull(MarketPriceService.parsePrice("not json"))
        assertNull(MarketPriceService.parsePrice("""{"bitcoin":{}}"""))
    }

    @Test
    fun `null on non-positive price`() {
        assertNull(MarketPriceService.parsePrice("""{"bitcoin":{"idr":0}}"""))
        assertNull(MarketPriceService.parsePrice("""{"bitcoin":{"idr":-5}}"""))
    }
}
