package com.neop2p.domain.usecase

import com.neop2p.domain.repository.OfferRepository
import kotlinx.coroutines.flow.Flow

class GetActiveOffersUseCase(private val offerRepository: OfferRepository) {
    fun invoke(): Flow<List<com.neop2p.domain.model.Offer>> = offerRepository.getActiveOffers()
}