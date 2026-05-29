package com.neop2p.data.remote

import com.neop2p.domain.model.Offer
import com.neop2p.domain.model.OfferStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emit
import kotlinx.coroutines.flow.flow

class OfferRemoteDataSource {
    suspend fun getActiveOffers(): Flow<Offer> = flow {
        // Simulate network delay
        delay(500)
        // Emit a list of mock offers
        emit(
            Offer(
                id = "offer_1",
                creatorId = "peer_1",
                asset = "BTC",
                amount = 0.01,
                price = 1500000.0, // IDR per BTC
                total = 15000.0,
                fee = 0.0001,
                paymentMethods = listOf("BCA", "GOPAY", "DANA"),
                isBuying = true,
                timestamp = System.currentTimeMillis() - 3600000,
                status = OfferStatus.ACTIVE
            )
        )
        emit(
            Offer(
                id = "offer_2",
                creatorId = "peer_2",
                asset = "BTC",
                amount = 0.05,
                price = 1480000.0,
                total = 74000.0,
                fee = 0.0005,
                paymentMethods = listOf("BCA", "DANA"),
                isBuying = false,
                timestamp = System.currentTimeMillis() - 7200000,
                status = OfferStatus.ACTIVE
            )
        )
    }

    // Other methods (getOfferById, saveOffer, etc.) would be implemented similarly
    // For brevity, we are only implementing what is needed for the HomeViewModel
}