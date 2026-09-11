package com.neop2p.data.market

import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the current BTC/IDR market price from a public API (H3, 2026-09-11).
 *
 * Returns null when the price is unavailable — the caller must NOT prefill a
 * stale placeholder (the old DEFAULT_BTC_MARKET_PRICE_IDR was ~35% below a
 * realistic price and silently mispriced offers). Results are cached for
 * [MARKET_PRICE_STALE_MS] so repeated screen entries do not hammer CoinGecko.
 */
@Singleton
class MarketPriceService @Inject constructor(
    private val httpClient: HttpClient
) {
    companion object {
        private const val TAG = "MarketPriceService"
        private const val COINGECKO_URL =
            "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=idr"
        const val MARKET_PRICE_STALE_MS: Long = 5 * 60 * 1000L

        /** Pure parser — unit-testable without a client. */
        fun parsePrice(json: String): Long? = try {
            val obj = Json.parseToJsonElement(json).jsonObject
            val price = obj["bitcoin"]?.jsonObject?.get("idr")?.jsonPrimitive?.content
                ?.toDoubleOrNull()
            if (price != null && price > 0) price.toLong() else null
        } catch (_: Exception) {
            null
        }
    }

    @Volatile
    private var cachedPrice: Long? = null
    @Volatile
    private var cachedAtMs: Long = 0L

    suspend fun getBtcPriceIdr(): Long? {
        val now = System.currentTimeMillis()
        cachedPrice?.let { if (now - cachedAtMs < MARKET_PRICE_STALE_MS) return it }
        return try {
            val response = httpClient.get(COINGECKO_URL)
            val price = parsePrice(response.bodyAsText())
            cachedPrice = price
            cachedAtMs = now
            if (price == null) Log.w(TAG, "Unexpected market price response")
            price
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch market price: ${e.message}")
            null
        }
    }
}
