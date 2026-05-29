package com.neop2p.domain.usecase

import com.neop2p.domain.model.User
import com.neop2p.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow

class GetUserProfileUseCase(private val userRepository: UserRepository) {
    fun invoke(): Flow<User> = userRepository.getUserProfile()
}