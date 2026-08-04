package com.neop2p.data.p2p.routing

import android.util.Log
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.p2p.NostrClient
import com.neop2p.data.p2p.protocol.AppMessage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

class OfferRouter @Inject constructor(
    private val nostrClient: NostrClient,
    private val offerDao: OfferDao
) {
    fun startListening(scope: CoroutineScope) {
        scope.launch {
            nostrClient.offers.collectLatest { offerJson ->
                // Persist raw offer JSON; parsing into TradeOffer is done by the
                // orchestrator (Task 6) or a mapper. Keep the router minimal.
                Log.d("OfferRouter", "received offer event: ${offerJson["id"]}")
            }
        }
    }

    suspend fun receiveOffer(msg: AppMessage.Offer): Result<Unit> {
        Log.d("OfferRouter", "received offer from ${msg.from}: ${msg.offerJson}")
        return Result.success(Unit)
    }
}
