package com.neop2p.data.market

/**
 * A normalized BTC price observation. At least one of [idr]/[usd] is non-null.
 * Keepers of an IDR price for offer prefill need `idr`; a USD-only source can be
 * converted by `MarketPriceService` using another provider's implied FX rate.
 */
data class BtcPrice(
    val idr: Long? = null,
    val usd: Long? = null,
)

/** A single BTC price source. Fail-closed: return `null` when unusable. */
interface PriceProvider {
    val id: String
    suspend fun fetch(): BtcPrice?
}
