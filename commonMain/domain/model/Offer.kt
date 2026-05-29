package com.neop2p.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Offer(
    val id: String,
    val creatorId: String, // peerId of the offer creator
    val asset: String,     // e.g., "BTC"
    val amount: Double,    // amount in BTC
    val price: Double,     // price per BTC in fiat (e.g., IDR)
    val total: Double,     // total fiat amount (amount * price)
    val fee: Double,       // fee in BTC (1% of amount)
    val paymentMethods: List<String>, // e.g., listOf("BCA", "GOPAY", "DANA")
    val isBuying: Boolean, // true if buyer is buying BTC, false if selling
    val timestamp: Long,   // Unix timestamp in milliseconds
    val status: OfferStatus = OfferStatus.ACTIVE
)

enum class OfferStatus {
    ACTIVE,
    INACTIVE,
    COMPLETED,
    CANCELLED
}