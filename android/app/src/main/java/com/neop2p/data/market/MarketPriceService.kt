package com.neop2p.data.market

import android.util.Log
import com.neop2p.NeoP2PConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the current BTC/IDR market price from a public API.
 *
 * Used to pre-fill the "Price per BTC (IDR)" field in the Create Offer form
 * with the live market price. The user can still edit the value.
 *
 * Falls back to [NeoP2PConfig.DEFAULT_BTC_MARKET_PRICE_IDR] if the API is
 * unreachable, so the form always has a sensible default.
 */
@Singleton
class MarketPriceService @Inject constructor(
    private val httpClient: HttpClient
) {
    companion object {
        private const val TAG = "MarketPriceService"

        // CoinGecko simple price endpoint — returns BTC price in IDR.
        // No API key required for low-volume public use.
        private const val COINGECKO_URL =
            "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=idr"
    }

    /**
     * Returns the current BTC price in IDR, or the static fallback if the
     * network call fails.
     */
    suspend fun getBtcPriceIdr(): Long {
        return try {
            val response = httpClient.get(COINGECKO_URL)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val price = json["bitcoin"]?.jsonObject?.get("idr")?.jsonPrimitive?.content
                ?.toDoubleOrNull()
            if (price != null && price > 0) {
                Log.d(TAG, "BTC/IDR market price: $price")
                price.toLong()
            } else {
                Log.w(TAG, "Unexpected market price response, using fallback")
                NeoP2PConfig.DEFAULT_BTC_MARKET_PRICE_IDR
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch market price, using fallback: ${e.message}")
            NeoP2PConfig.DEFAULT_BTC_MARKET_PRICE_IDR
        }
    }
}
