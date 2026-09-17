package com.neop2p.data.market.provider

import com.neop2p.data.market.BtcPrice
import com.neop2p.data.market.PriceProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CoinPaprika ticker provider. Returns both IDR and USD, so it doubles as the
 * FX source for USD-only providers (`idr / usd` = rupiah per USD).
 */
class CoinPaprikaPriceProvider(
    private val httpClient: HttpClient,
) : PriceProvider {

    override val id: String = "coinpaprika"

    companion object {
        const val URL: String = "https://api.coinpaprika.com/v1/tickers/btc-bitcoin"

        /** Pure parser — unit-testable without a client. */
        fun parse(json: String): BtcPrice? = try {
            val quotes = Json.parseToJsonElement(json).jsonObject["quotes"]?.jsonObject
            val idr = quotes?.get("IDR")?.jsonObject?.get("price")?.jsonPrimitive?.content
                ?.toDoubleOrNull()?.takeIf { it > 0 }?.toLong()
            val usd = quotes?.get("USD")?.jsonObject?.get("price")?.jsonPrimitive?.content
                ?.toDoubleOrNull()?.takeIf { it > 0 }?.toLong()
            if (idr == null && usd == null) null else BtcPrice(idr = idr, usd = usd)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun fetch(): BtcPrice? = try {
        parse(httpClient.get(URL).bodyAsText())
    } catch (e: Exception) {
        null
    }
}
