package com.neop2p.data.p2p

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionBroadcasterTest {

    private class FakeSender : ResolutionSender {
        val sent = mutableListOf<Pair<String, String>>()
        var failing: Set<String> = emptySet()
        override suspend fun sendResolution(
            toPeerId: String,
            escrowId: String,
            decision: String,
            arbitratorSigHex: String,
            notes: String?,
            sellerRefundAddress: String?,
            signedTxHex: String?,
        ): Boolean {
            sent.add(toPeerId to escrowId)
            return toPeerId !in failing
        }
    }

    private class FakeStore : ResolutionStore {
        val rows = linkedMapOf<String, PendingResolution>()
        override fun save(resolution: PendingResolution) { rows[resolution.escrowId] = resolution }
        override fun load(escrowId: String): PendingResolution? = rows[escrowId]
        override fun all(): List<PendingResolution> = rows.values.toList()
        override fun remove(escrowId: String) { rows.remove(escrowId) }
        override fun clear() { rows.clear() }
    }

    private fun pending(escrowId: String = "esc-1", targets: List<String> = listOf("buyer", "seller")) =
        PendingResolution(
            escrowId = escrowId, decision = "RELEASE_TO_BUYER", arbitratorSigHex = "sig",
            notes = "n", sellerRefundAddress = "addr", signedTxHex = "tx", targets = targets,
        )

    // ── broadcast (first attempt) ──

    @Test fun `broadcast removes the row and returns true when all targets ack`() = runTest {
        val sender = FakeSender()
        val store = FakeStore()
        val b = ResolutionBroadcaster(sender, store)

        assertTrue(b.broadcast(pending()))
        assertNull(store.load("esc-1"))
        assertEquals(2, sender.sent.size)
    }

    @Test fun `broadcast persists only the failed target on a partial`() = runTest {
        val sender = FakeSender().apply { failing = setOf("buyer") }
        val store = FakeStore()
        val b = ResolutionBroadcaster(sender, store)

        assertFalse(b.broadcast(pending()))
        assertEquals(listOf("buyer"), store.load("esc-1")?.targets)
    }

    @Test fun `broadcast persists all targets when every send fails`() = runTest {
        val sender = FakeSender().apply { failing = setOf("buyer", "seller") }
        val store = FakeStore()
        val b = ResolutionBroadcaster(sender, store)

        assertFalse(b.broadcast(pending()))
        assertEquals(listOf("buyer", "seller"), store.load("esc-1")?.targets)
    }

    // ── retryAll (sweep) ──

    @Test fun `retryAll removes a row once every target acks`() = runTest {
        val sender = FakeSender().apply { failing = setOf("buyer") }
        val store = FakeStore()
        val b = ResolutionBroadcaster(sender, store)
        b.broadcast(pending())

        sender.failing = emptySet()
        assertEquals(1, b.retryAll())
        assertNull(store.load("esc-1"))
    }

    @Test fun `retryAll keeps the row with all targets when every send still fails`() = runTest {
        // Regression for the pre-1c inverted loop: an all-fail sweep used to
        // DELETE the pending resolution (a resolution delivered to nobody was
        // silently lost). It must be retained for the next sweep.
        val sender = FakeSender().apply { failing = setOf("buyer", "seller") }
        val store = FakeStore()
        val b = ResolutionBroadcaster(sender, store)
        b.broadcast(pending())

        assertEquals(0, b.retryAll())
        assertEquals(listOf("buyer", "seller"), store.load("esc-1")?.targets)
    }

    @Test fun `retryAll keeps only the still-failed target on a partial`() = runTest {
        val sender = FakeSender().apply { failing = setOf("buyer", "seller") }
        val store = FakeStore()
        val b = ResolutionBroadcaster(sender, store)
        b.broadcast(pending())

        sender.failing = setOf("seller")
        assertEquals(0, b.retryAll())
        assertEquals(listOf("seller"), store.load("esc-1")?.targets)
        // The still-delivered peer from the partial is not re-sent next time.
        val before = sender.sent.size
        sender.failing = emptySet()
        assertEquals(1, b.retryAll())
        assertEquals(before + 1, sender.sent.size)
        assertNull(store.load("esc-1"))
    }

    @Test fun `retryAll drops an already-resolved dispute without sending`() = runTest {
        val sender = FakeSender().apply { failing = setOf("buyer", "seller") }
        val store = FakeStore()
        val b = ResolutionBroadcaster(sender, store, isDisputeResolved = { it == "esc-1" })
        store.save(pending())

        val sendsBefore = sender.sent.size
        assertEquals(1, b.retryAll())
        assertNull(store.load("esc-1"))
        assertEquals(sendsBefore, sender.sent.size)
    }

    @Test fun `retryAll on an empty store is a no-op`() = runTest {
        assertEquals(0, ResolutionBroadcaster(FakeSender(), FakeStore()).retryAll())
    }
}
