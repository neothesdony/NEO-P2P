package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Slice 4 (2026-09-01): dispute-delivery gate — the COUNTERPARTY is the ONLY
 * gate. The arbitrator is never part of the verdict: an offline arbitrator
 * must not block a party's dispute from opening (the sweep retries later).
 */
class EscrowDisputeDeliveryVerdictTest {

    @Test
    fun `counterparty delivered opens the dispute`() {
        assertEquals(true, EscrowService.disputeDeliveryVerdict(true))
    }

    @Test
    fun `counterparty not delivered never opens`() {
        assertEquals(false, EscrowService.disputeDeliveryVerdict(false))
    }
}
