package com.neop2p.data.network.provider

import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.data.network.Capability
import com.neop2p.data.network.ExplorerProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * bitcoiner.live fee-estimate adapter — FEES only.
 *
 * NOTE: its data is published under CC BY-NC-SA (non-commercial), so the
 * registry keeps this provider DISABLED by default. See
 * `ExplorerRegistry.BITCOINER_LIVE_ENABLED`.
 */
class BitcoinerLiveProvider(
    private val httpClient: HttpClient,
) : ExplorerProvider {

    override val id: String = "bitcoiner.live"

    override val capabilities: Set<Capability> = setOf(Capability.FEES)

    companion object {
        const val BASE: String = "https://bitcoiner.live"
        private const val URL: String = "$BASE/api/fees/estimates/latest?confidence=0.9"

        /**
         * Pure parser. Estimates are keyed by target confirmation in minutes
         * (e.g. "30", "60", "120"); they are taken in ascending order as
         * fastest / halfHour / hour and normalized monotonic by the facade.
         */
        fun parseFeeEstimate(json: String): ChainMonitor.FeeEstimate? {
            val estimates = try {
                Json.parseToJsonElement(json).jsonObject["estimates"]?.jsonObject
            } catch (e: Exception) {
                return null
            } ?: return null
            val rates = estimates.entries
                .mapNotNull { (k, v) ->
                    val minutes = k.toIntOrNull() ?: return@mapNotNull null
                    val rate = v.jsonObject["sat_per_vbyte"]?.jsonPrimitive?.content
                        ?.toDoubleOrNull()?.roundToLong() ?: return@mapNotNull null
                    minutes to rate
                }
                .sortedBy { it.first }
                .map { it.second }
            if (rates.isEmpty()) return null
            return ChainMonitor.FeeEstimate(
                fastest = rates[0],
                halfHour = rates.getOrElse(1) { rates[0] },
                hour = rates.getOrElse(2) { rates.last() }
            )
        }
    }

    override suspend fun feeEstimate(): ChainMonitor.FeeEstimate? = try {
        parseFeeEstimate(httpClient.get(URL).bodyAsText())
    } catch (e: Exception) {
        null
    }
}
