package com.neop2p.data.repository

import com.neop2p.domain.model.User
import com.neop2p.domain.repository.UserRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emit
import kotlinx.coroutines.flow.flow

class UserRepositoryImpl : UserRepository {
    override fun getUserProfile(): Flow<User> = flow {
        // Simulate network delay or database access
        delay(300)
        emit(
            User(
                id = "peer_1234567890abcdef",
                nickname = "CryptoTrader",
                avatarUrl = null,
                reputationScore = 0.95f,
                totalTrades = 42,
                completedTrades = 38,
                disputedTrades = 2,
                isVerified = true,
                verification = com.neop2p.domain.model.VerificationStatus(
                    emailVerified = true,
                    phoneVerified = true,
                    idVerified = true
                ),
                createdAt = System.currentTimeMillis() - 86400000, // 1 day ago
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    override fun updateNickname(nickname: String) = TODO("Not yet implemented")
    override fun verifyEmail() = TODO("Not yet implemented")
    override fun verifyPhone() = TODO("Not yet implemented")
}