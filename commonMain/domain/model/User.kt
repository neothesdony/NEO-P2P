package com.neop2p.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String, // peerId
    val nickname: String,
    val avatarUrl: String? = null,
    val reputationScore: Float, // 0.0 to 1.0
    val totalTrades: Int,
    val completedTrades: Int,
    val disputedTrades: Int,
    val isVerified: Boolean = false,
    val verification: VerificationStatus? = null,
    val createdAt: Long, // Unix timestamp
    val updatedAt: Long
)

@Serializable
data class VerificationStatus(
    val emailVerified: Boolean = false,
    val phoneVerified: Boolean = false,
    val idVerified: Boolean = false
)

enum class TransactionStatus {
    PENDING,
    CONFIRMED,
    COMPLETED,
    CANCELLED,
    DISPUTED
}

@Serializable
data class Transaction(
    val id: String,
    val offerId: String,
    val buyerId: String,
    val sellerId: String,
    val asset: String, // e.g., "BTC"
    val amount: Double, // amount in BTC
    val price: Double, // price per BTC in fiat
    val total: Double, // total fiat
    val fee: Double, // fee in BTC
    val status: TransactionStatus,
    val createdAt: Long,
    val updatedAt: Long
)