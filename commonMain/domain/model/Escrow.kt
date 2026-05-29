package com.neop2p.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Escrow(
    val id: String,
    val offerId: String,
    val buyerId: String,
    val sellerId: String,
    val asset: String, // e.g., "BTC"
    val amount: Double, // amount in BTC
    val price: Double, // price per BTC in fiat
    val total: Double, // total fiat
    val fee: Double, // fee in BTC
    val status: EscrowStatus,
    val createdAt: Long,
    val updatedAt: Long
)

enum class EscrowStatus {
    FUNDING, // waiting for buyer to pay
    FUNDED, // buyer has paid, seller to release
    RELEASED, // funds released to seller
    DISPUTED, // in dispute
    REFUNDED // funds returned to buyer
}