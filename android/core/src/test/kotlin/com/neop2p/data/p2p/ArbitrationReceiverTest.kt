package com.neop2p.data.p2p

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host-agnostic arbitration receive path, exercised with in-memory doubles
 * (the spec's `TransportGateway` fake). This is the daemon's ingest spec: the
 * same F1 binding check, F4 rate limit / caps, and persisted shape the app
 * applies — without a device or a live RNS session.
 */
class ArbitrationReceiverTest {

    private val now = 1_700_000_000_000L

    private class FakeTransport : TransportGateway {
        private val _incoming =
            MutableSharedFlow<P2PTransport.TransportMessage>(replay = 1, extraBufferCapacity = 128)
        override val incomingMessages: SharedFlow<P2PTransport.TransportMessage> = _incoming
        var verified: (String, String) -> Boolean = { _, _ -> true }
        override fun isVerifiedSender(peerId: String, senderDestHash: String): Boolean =
            verified(peerId, senderDestHash)

        suspend fun emit(message: P2PTransport.TransportMessage) = _incoming.emit(message)
    }

    private class FakeDisputeStore : DisputeStore {
        val rows = LinkedHashMap<String, DisputeRecord>()
        override fun getById(escrowId: String): DisputeRecord? = rows[escrowId]
        override fun countUnresolvedBySender(openedBy: String): Int =
            rows.values.count { it.openedBy == openedBy && !it.resolved }
        override fun upsert(record: DisputeRecord) {
            rows[record.escrowId] = record
        }
        override fun markResolved(escrowId: String) {
            rows[escrowId]?.let { rows[escrowId] = it.copy(resolved = true) }
        }
        override fun all(): List<DisputeRecord> = rows.values.sortedByDescending { it.openedAt }
        override fun clear() = rows.clear()
    }

    private class FakeEvidenceStore : EvidenceStore {
        val rows = mutableListOf<EvidenceRecord>()
        override fun forEscrow(escrowId: String): List<EvidenceRecord> =
            rows.filter { it.escrowId == escrowId }
        override fun insert(record: EvidenceRecord) {
            rows += record
        }
        override fun all(): List<EvidenceRecord> = rows
        override fun clear() = rows.clear()
    }

    private fun disputeMessage(
        escrowId: String = "esc-1",
        from: String = "buyer",
        dest: String = "dest-buyer",
        openedBy: String = "buyer",
        buyerPeerId: String = "buyer",
        sellerPeerId: String = "seller",
    ) = P2PTransport.TransportMessage(
        type = ArbitrationIngest.TYPE_DISPUTE,
        fromPeerId = from,
        authenticated = true,
        senderDestHash = dest,
        data = buildString {
            append("{\"escrow_id\":\"").append(escrowId).append("\"")
            append(",\"opened_by\":\"").append(openedBy).append("\"")
            append(",\"reason\":\"not paid\"")
            append(",\"opened_at\":").append(now)
            append(",\"buyer_peer_id\":\"").append(buyerPeerId).append("\"")
            append(",\"seller_peer_id\":\"").append(sellerPeerId).append("\"")
            append("}")
        }.toByteArray(Charsets.UTF_8),
    )

    private fun evidenceMessage(
        escrowId: String = "esc-1",
        from: String = "buyer",
        dest: String = "dest-buyer",
        submitter: String = "buyer",
        description: String = "receipt",
        image: ByteArray? = byteArrayOf(1, 2, 3),
    ) = P2PTransport.TransportMessage(
        type = ArbitrationIngest.TYPE_EVIDENCE,
        fromPeerId = from,
        authenticated = true,
        senderDestHash = dest,
        data = buildString {
            append("{\"escrow_id\":\"").append(escrowId).append("\"")
            append(",\"submitter\":\"").append(submitter).append("\"")
            append(",\"description\":\"").append(description).append("\"")
            append(",\"mime_type\":\"image/jpeg\"")
            val b64 = image?.let { java.util.Base64.getEncoder().encodeToString(it) } ?: ""
            append(",\"image_base64\":\"").append(b64).append("\"")
            append("}")
        }.toByteArray(Charsets.UTF_8),
    )

    private fun record(
        escrowId: String = "esc-1",
        openedBy: String = "buyer",
        resolved: Boolean = false,
    ) = DisputeRecord(
        escrowId = escrowId, openedBy = openedBy, reason = "r", openedAt = 1L,
        redeemScriptHex = null, psbtHex = null, refundTxHex = null, depositSats = null,
        fundingScriptType = null, sellerRefundAddress = null,
        buyerPeerId = "buyer", sellerPeerId = "seller",
        buyerBtcAddress = null, buyerPubkeyHex = null, sellerPubkeyHex = null,
        sellerRefundAttestation = null, buyerAddressAttestation = null,
        offerId = null, tradeSats = null, receivedAt = 1L, resolved = resolved,
    )

    private fun receiver(
        transport: FakeTransport = FakeTransport(),
        disputes: FakeDisputeStore = FakeDisputeStore(),
        evidence: FakeEvidenceStore = FakeEvidenceStore(),
        localEscrowExists: (String) -> Boolean = { false },
        rateLimiter: PerPeerRateLimiter = PerPeerRateLimiter(),
    ) = ArbitrationReceiver(
        transport = transport,
        disputes = disputes,
        evidence = evidence,
        localEscrowExists = localEscrowExists,
        nowMs = { now },
        rateLimiter = rateLimiter,
    )

    @Test
    fun `run ingests a dispute emitted on the transport flow`() = runTest {
        val transport = FakeTransport()
        val disputes = FakeDisputeStore()
        val receiver = receiver(transport = transport, disputes = disputes)
        val job = launch { receiver.run() }
        runCurrent()

        transport.emit(disputeMessage())
        advanceUntilIdle()
        job.cancel()

        assertEquals(1, disputes.all().size)
        assertEquals("esc-1", disputes.all().first().escrowId)
    }

    @Test
    fun `unverified sender dispute is dropped`() = runTest {
        val transport = FakeTransport().apply { verified = { _, _ -> false } }
        val disputes = FakeDisputeStore()
        val receiver = receiver(transport = transport, disputes = disputes)

        receiver.handle(disputeMessage())

        assertEquals(0, disputes.all().size)
    }

    @Test
    fun `unverified sender evidence is dropped`() = runTest {
        val transport = FakeTransport().apply { verified = { _, _ -> false } }
        val disputes = FakeDisputeStore().apply { upsert(record()) }
        val evidence = FakeEvidenceStore()
        val receiver = receiver(transport = transport, disputes = disputes, evidence = evidence)

        receiver.handle(evidenceMessage())

        assertEquals(0, evidence.all().size)
    }

    @Test
    fun `unhandled message types are ignored`() = runTest {
        val disputes = FakeDisputeStore()
        val evidence = FakeEvidenceStore()
        val receiver = receiver(disputes = disputes, evidence = evidence)

        receiver.handle(
            P2PTransport.TransportMessage(
                type = "resolution", fromPeerId = "buyer", data = "{}".toByteArray()
            )
        )
        receiver.handle(
            P2PTransport.TransportMessage(
                type = "chat", fromPeerId = "buyer", data = "hi".toByteArray()
            )
        )

        assertEquals(0, disputes.all().size)
        assertEquals(0, evidence.all().size)
    }

    @Test
    fun `verified new dispute is persisted unresolved`() = runTest {
        val disputes = FakeDisputeStore()
        val receiver = receiver(disputes = disputes)

        receiver.handle(disputeMessage())

        val saved = disputes.getById("esc-1")
        assertNotNull(saved)
        assertEquals("buyer", saved!!.openedBy)
        assertEquals("not paid", saved.reason)
        assertTrue(!saved.resolved)
    }

    @Test
    fun `redelivery of a resolved dispute is not re-persisted`() = runTest {
        val disputes = FakeDisputeStore().apply { upsert(record(resolved = true)) }
        val receiver = receiver(disputes = disputes)
        val before = disputes.getById("esc-1")!!.receivedAt

        receiver.handle(disputeMessage())

        assertEquals(before, disputes.getById("esc-1")!!.receivedAt)
    }

    @Test
    fun `evidence for an unknown escrow is dropped`() = runTest {
        val disputes = FakeDisputeStore()
        val evidence = FakeEvidenceStore()
        val receiver = receiver(disputes = disputes, evidence = evidence)

        receiver.handle(evidenceMessage(escrowId = "ghost"))

        assertEquals(0, evidence.all().size)
    }

    @Test
    fun `evidence whose submitter is not the sender is dropped`() = runTest {
        val disputes = FakeDisputeStore().apply { upsert(record()) }
        val evidence = FakeEvidenceStore()
        val receiver = receiver(disputes = disputes, evidence = evidence)

        receiver.handle(evidenceMessage(submitter = "stranger"))

        assertEquals(0, evidence.all().size)
    }

    @Test
    fun `evidence for a known dispute is persisted`() = runTest {
        val disputes = FakeDisputeStore().apply { upsert(record()) }
        val evidence = FakeEvidenceStore()
        val receiver = receiver(disputes = disputes, evidence = evidence)

        receiver.handle(evidenceMessage())

        assertEquals(1, evidence.all().size)
        assertEquals("esc-1", evidence.all().first().escrowId)
    }

    @Test
    fun `duplicate evidence is not inserted twice`() = runTest {
        val disputes = FakeDisputeStore().apply { upsert(record()) }
        val evidence = FakeEvidenceStore()
        val receiver = receiver(disputes = disputes, evidence = evidence)

        receiver.handle(evidenceMessage())
        receiver.handle(evidenceMessage())

        assertEquals(1, evidence.all().size)
    }

    @Test
    fun `rate limit drops messages beyond the burst`() = runTest {
        val disputes = FakeDisputeStore()
        // maxBurst=2, no refill, fixed clock.
        val receiver = receiver(
            disputes = disputes,
            rateLimiter = PerPeerRateLimiter(maxBurst = 2, refillPerSecond = 0.0),
        )

        receiver.handle(disputeMessage(escrowId = "e1"))
        receiver.handle(disputeMessage(escrowId = "e2"))
        receiver.handle(disputeMessage(escrowId = "e3"))

        assertEquals(2, disputes.all().size)
        assertNull(disputes.getById("e3"))
    }

    @Test
    fun `rate limit is keyed by the sender destination not the claimed peer id`() = runTest {
        val disputes = FakeDisputeStore()
        val receiver = receiver(
            disputes = disputes,
            rateLimiter = PerPeerRateLimiter(maxBurst = 1, refillPerSecond = 0.0),
        )

        // Same destination, two different self-asserted peerIds: one bucket.
        receiver.handle(disputeMessage(escrowId = "e1", from = "buyer", dest = "shared"))
        receiver.handle(disputeMessage(escrowId = "e2", from = "seller", dest = "shared"))

        assertEquals(1, disputes.all().size)
        assertEquals("e1", disputes.all().first().escrowId)
    }

    @Test
    fun `malformed dispute json does not throw`() = runTest {
        val disputes = FakeDisputeStore()
        val receiver = receiver(disputes = disputes)

        receiver.handle(
            P2PTransport.TransportMessage(
                type = ArbitrationIngest.TYPE_DISPUTE,
                fromPeerId = "buyer",
                senderDestHash = "dest-buyer",
                data = "{not-json".toByteArray(Charsets.UTF_8),
            )
        )

        assertEquals(0, disputes.all().size)
    }
}
