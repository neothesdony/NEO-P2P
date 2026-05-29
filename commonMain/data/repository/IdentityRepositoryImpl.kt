package com.neop2p.data.repository

import com.neop2p.domain.model.Identity
import com.neop2p.domain.repository.IdentityRepository
import com.neop2p.data.identity.IdentityDataSource
import javax.inject.Inject

class IdentityRepositoryImpl @Inject constructor(
    private val identityDataSource: IdentityDataSource
) : IdentityRepository {
    override fun getIdentity() = identityDataSource.getIdentity()

    override fun saveIdentity(identity: Identity) = identityDataSource.saveIdentity(identity)
}