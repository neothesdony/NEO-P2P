package com.neop2p.data.market.provider

import com.neop2p.data.market.BtcPrice
import com.neop2p.data.market.PriceProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** CoinGecko simple-price provider (existing source, now IDR + USD). */
class CoinGeckoPriceProvider(
    private val httpClient: HttpClient,
) : PriceProvider {

    override val id: String = "coingecko"

    companion object {
        const val URL: String =
            "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=idr,usd"

        /** Pure parser — unit-testable without a client. */
        fun parse(json: String): BtcPrice? = try {
            val bitcoin = Json.parseToJsonElement(json).jsonObject["bitcoin"]?.jsonObject
            val idr = bitcoin?.get("idr")?.jsonPrimitive?.content?.toDoubleOrNull()
                ?.takeIf { it > 0 }?.toLong()
            val usd = bitcoin?.get("usd")?.jsonPrimitive?.content?.toDoubleOrNull()
                ?.takeIf { it > 0 }?.toLong()
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
