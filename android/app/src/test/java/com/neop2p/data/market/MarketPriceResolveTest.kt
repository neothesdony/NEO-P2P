package com.neop2p.data.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketPriceResolveTest {

    @Test
    fun `idr-native observation is preferred in order`() {
        val price = MarketPriceService.resolveBtcIdr(
            coinGecko = BtcPrice(idr = 1_500_000_000, usd = 90_000),
            coinPaprika = BtcPrice(idr = 1_400_000_000, usd = 88_000),
        )
        assertEquals(1_500_000_000L, price)
    }

    @Test
    fun `coinpaprika idr is used when coingecko fails`() {
        val price = MarketPriceService.resolveBtcIdr(
            coinGecko = null,
            coinPaprika = BtcPrice(idr = 1_450_000_000, usd = 89_000),
        )
        assertEquals(1_450_000_000L, price)
    }

    @Test
    fun `usd-only observations cannot yield idr`() {
        assertNull(
            MarketPriceService.resolveBtcIdr(
                coinGecko = BtcPrice(usd = 90_000),
                coinPaprika = BtcPrice(usd = 88_000),
            )
        )
    }

    @Test
    fun `all sources missing is null`() {
        assertNull(MarketPriceService.resolveBtcIdr(null, null))
    }
}
