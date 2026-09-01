package com.neop2p.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Slice 3 (2026-09-01): serialization round-trip for the durable
 * evidence/resolution retry queue. Mirrors TransportNodeStoreTest — the pure
 * companion serializers are exactly what save() persists and parse() reads,
 * so plain JUnit exercises the write path without an Android Context.
 */
class PendingArbitrationStoreTest {

    private val evidence = PendingArbitrationStore.PendingEvidence(
        escrowId = "escrow_1",
        submitter = "peerA",
        description = "receipt",
        mimeType = "image/jpeg",
        imageBase64 = "aGVsbG8gd29ybGQ=",
        targets = listOf("peerB", "arbPeer")
    )

    private val resolution = PendingArbitrationStore.PendingResolution(
        escrowId = "escrow_1",
        decision = "RELEASE_TO_BUYER",
        arbitratorSigHex = "deadbeef",
        notes = "buyer paid",
        sellerRefundAddress = "tb1qrefund",
        signedTxHex = "txhex",
        targets = listOf("peerA", "peerB")
    )

    @Test
    fun `evidence round-trips through serialize`() {
        val parsed = PendingArbitrationStore.parseEvidence(evidence.escrowId, PendingArbitrationStore.toJson(evidence))
        assertEquals(evidence, parsed)
    }

    @Test
    fun `evidence with empty targets round-trips`() {
        val p = evidence.copy(targets = emptyList())
        val parsed = PendingArbitrationStore.parseEvidence(p.escrowId, PendingArbitrationStore.toJson(p))
        assertEquals(p, parsed)
    }

    @Test
    fun `resolution round-trips through serialize`() {
        val parsed = PendingArbitrationStore.parseResolution(resolution.escrowId, PendingArbitrationStore.toJson(resolution))
        assertEquals(resolution, parsed)
    }

    @Test
    fun `resolution with null optionals round-trips`() {
        val p = resolution.copy(notes = null, sellerRefundAddress = null, signedTxHex = null)
        val parsed = PendingArbitrationStore.parseResolution(p.escrowId, PendingArbitrationStore.toJson(p))
        assertEquals(p, parsed)
    }

    @Test
    fun `garbage json yields null`() {
        assertNull(PendingArbitrationStore.parseEvidence("e1", "{not json"))
        assertNull(PendingArbitrationStore.parseResolution("e1", ""))
    }
}
