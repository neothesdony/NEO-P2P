package com.neop2p.domain.repository

import com.neop2p.domain.model.Offer
import com.neop2p.domain.model.OfferStatus
import kotlinx.coroutines.flow.Flow

interface OfferRepository {
    fun getActiveOffers(): Flow<List<Offer>>
    fun getOfferById(offerId: String): Flow<Offer?>
    fun saveOffer(offer: Offer)
    fun updateOfferStatus(offerId: String, status: OfferStatus)
    fun deleteOffer(offerId: String)
}