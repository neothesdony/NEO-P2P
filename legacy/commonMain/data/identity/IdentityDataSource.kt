package com.neop2p.data.identity

import com.neop2p.domain.model.Identity
import kotlinx.coroutines.flow.Flow

interface IdentityDataSource {
    fun getIdentity(): Flow<Identity?>
    fun saveIdentity(identity: Identity)
}