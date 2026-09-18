package com.neop2p.data.market

import android.util.Log
import com.neop2p.data.market.provider.CoinGeckoPriceProvider
import com.neop2p.data.market.provider.CoinPaprikaPriceProvider
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the current BTC/IDR market price from an ordered set of providers.
 *
 * Returns null when the price is unavailable — the caller must NOT prefill a
 * stale placeholder. Results are cached for [MARKET_PRICE_STALE_MS] so repeated
 * screen entries do not hammer the providers.
 *
 * Sources: CoinGecko then CoinPaprika — both return IDR directly, so there is
 * no conversion step and no dedicated FX domain. No provider supplies IDR →
 * null (Create Offer leaves the price field blank rather than mispricing an
 * offer).
 */
@Singleton
class MarketPriceService internal constructor(
    private val providers: List<PriceProvider>,
) {

    @Inject
    constructor(httpClient: HttpClient) : this(defaultProviders(httpClient))

    companion object {
        private const val TAG = "MarketPriceService"
        const val MARKET_PRICE_STALE_MS: Long = 5 * 60 * 1000L

        private fun defaultProviders(httpClient: HttpClient): List<PriceProvider> = listOf(
            CoinGeckoPriceProvider(httpClient),
            CoinPaprikaPriceProvider(httpClient),
        )

        /** Pure CoinGecko parser kept for existing callers/tests. */
        fun parsePrice(json: String): Long? = CoinGeckoPriceProvider.parse(json)?.idr

        /** Pure resolution: first non-null, positive IDR observation wins. */
        fun resolveBtcIdr(coinGecko: BtcPrice?, coinPaprika: BtcPrice?): Long? =
            coinGecko?.idr?.takeIf { it > 0 } ?: coinPaprika?.idr?.takeIf { it > 0 }
    }

    @Volatile
    private var cachedPrice: Long? = null
    @Volatile
    private var cachedAtMs: Long = 0L

    suspend fun getBtcPriceIdr(): Long? {
        val now = System.currentTimeMillis()
        cachedPrice?.let { if (now - cachedAtMs < MARKET_PRICE_STALE_MS) return it }

        val coinGecko = fetch("coingecko")
        coinGecko?.idr?.takeIf { it > 0 }?.let { return cache(it, now) }
        val coinPaprika = fetch("coinpaprika")
        coinPaprika?.idr?.takeIf { it > 0 }?.let { return cache(it, now) }

        Log.w(TAG, "No provider returned a usable BTC/IDR price")
        return cache(null, now)
    }

    private suspend fun fetch(id: String): BtcPrice? =
        providers.firstOrNull { it.id == id }?.let { runCatching { it.fetch() }.getOrNull() }

    private fun cache(price: Long?, now: Long): Long? {
        cachedPrice = price
        cachedAtMs = now
        return price
    }
}
