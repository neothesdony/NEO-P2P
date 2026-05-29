package com.neop2p.data.repository

import com.neop2p.domain.model.Offer
import com.neop2p.domain.model.OfferStatus
import com.neop2p.domain.repository.OfferRepository
import com.neop2p.data.remote.OfferRemoteDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

class OfferRepositoryImpl(private val remoteDataSource: OfferRemoteDataSource) : OfferRepository {
    override fun getActiveOffers(): Flow<List<Offer>> = remoteDataSource.getActiveOffers().toList()

    override fun getOfferById(offerId: String): Flow<Offer?> = TODO("Not yet implemented")
    override fun saveOffer(offer: Offer) = TODO("Not yet implemented")
    override fun updateOfferStatus(offerId: String, status: OfferStatus) = TODO("Not yet implemented")
    override fun deleteOffer(offerId: String) = TODO("Not yet implemented")
}