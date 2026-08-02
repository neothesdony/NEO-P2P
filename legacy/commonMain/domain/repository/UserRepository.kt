package com.neop2p.domain.repository

import com.neop2p.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun getUserProfile(): Flow<User>
    fun updateNickname(nickname: String)
    fun verifyEmail()
    fun verifyPhone()
}