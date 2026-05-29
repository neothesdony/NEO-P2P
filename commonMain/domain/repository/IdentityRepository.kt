package com.neop2p.domain.repository

import com.neop2p.domain.model.Identity
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    fun getIdentity(): Flow<Identity?>
    fun saveIdentity(identity: Identity)
}