package com.neop2p.data.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F4 pure ingest gates (2026-09-12): evidence for an unknown escrow is dropped,
 * and NEW disputes from a single sender are capped — re-deliveries of already
 * persisted disputes always pass (idempotency wins).
 */
class IngestGatesTest {

    @Test
    fun `evidence requires a known dispute row or a local escrow`() {
        assertFalse(EvidenceIngestGate.shouldPersist(hasDisputeRow = false, hasLocalEscrow = false))
        assertTrue(EvidenceIngestGate.shouldPersist(hasDisputeRow = true, hasLocalEscrow = false))
        assertTrue(EvidenceIngestGate.shouldPersist(hasDisputeRow = false, hasLocalEscrow = true))
    }

    @Test
    fun `new disputes are capped at the boundary`() {
        assertTrue(DisputeIngestGate.withinCap(isNew = true, unresolvedFromSender = 24))
        assertFalse(DisputeIngestGate.withinCap(isNew = true, unresolvedFromSender = 25))
    }

    @Test
    fun `re-delivery of a persisted dispute is never capped`() {
        assertTrue(DisputeIngestGate.withinCap(isNew = false, unresolvedFromSender = 25))
    }

    @Test
    fun `dispute opener must be the authenticated sender`() {
        assertTrue(DisputeIngestGate.openedByIsSender("peerA", "peerA"))
        assertFalse(DisputeIngestGate.openedByIsSender("peerB", "peerA"))
        assertFalse(DisputeIngestGate.openedByIsSender("", "peerA"))
    }

    @Test
    fun `sender-equality gate is scoped to new disputes so heal republish passes`() {
        // NEW dispute from a mismatched opener -> dropped.
        assertFalse(DisputeIngestGate.acceptOpenedBy(isNew = true, openedBy = "opener", fromPeerId = "other"))
        assertTrue(DisputeIngestGate.acceptOpenedBy(isNew = true, openedBy = "opener", fromPeerId = "opener"))
        // EXISTING row (re-delivery / healDisputePsbt republish by the
        // counterparty, whose peerId != opened_by) -> must always pass.
        assertTrue(DisputeIngestGate.acceptOpenedBy(isNew = false, openedBy = "opener", fromPeerId = "counterparty"))
        assertTrue(DisputeIngestGate.acceptOpenedBy(isNew = false, openedBy = "", fromPeerId = "counterparty"))
    }
}
