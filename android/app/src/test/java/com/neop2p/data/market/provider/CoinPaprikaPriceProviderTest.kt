package com.neop2p.data.market.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoinPaprikaPriceProviderTest {

    @Test
    fun `parses idr and usd quotes`() {
        val json = """
            {"id":"btc-bitcoin","quotes":{
              "USD":{"price":107077.69},"IDR":{"price":1750000000.0}}}
        """.trimIndent()
        val p = CoinPaprikaPriceProvider.parse(json)!!
        assertEquals(1_750_000_000L, p.idr)
        assertEquals(107_077L, p.usd)
    }

    @Test
    fun `missing quotes is null`() {
        assertNull(CoinPaprikaPriceProvider.parse("not json"))
        assertNull(CoinPaprikaPriceProvider.parse("""{"id":"btc-bitcoin"}"""))
    }
}
