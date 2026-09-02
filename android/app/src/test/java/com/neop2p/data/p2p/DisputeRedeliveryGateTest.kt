package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Dispute re-delivery gate (2026-09-02): the LXMF router retries a DIRECT
 * message until it gets a delivery receipt, and the party's 60s sweep
 * re-sends pending disputes — so the same dispute event can arrive many
 * times. Once the arbitrator resolved the dispute, every re-delivery is
 * stale and must be dropped (no re-persist, no re-notify).
 */
class DisputeRedeliveryGateTest {

    @Test
    fun `unresolved dispute is processed`() {
        assertEquals(true, P2POrchestrator.shouldProcessDispute(false))
    }

    @Test
    fun `resolved dispute is dropped`() {
        assertEquals(false, P2POrchestrator.shouldProcessDispute(true))
    }
}
