package com.neop2p.data.p2p

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the evidence retry polarity. The app's pre-fix loop
 * used `targets.filter { sendOk }`, i.e. it retained DELIVERED targets: an
 * all-fail sweep deleted the row (evidence silently lost) and a partial saved
 * the wrong peer. Mirrors [ResolutionBroadcasterTest]'s all-fail/partial cases.
 */
class EvidenceRetryTest {

    private suspend fun sweep(targets: List<String>, send: (String) -> Boolean): List<String> =
        EvidenceRetry.undeliveredTargets(targets, send)

    @Test fun `an all-ack attempt leaves nothing to retry`() = runTest {
        val attempted = mutableListOf<String>()
        val remaining = sweep(listOf("buyer", "seller")) {
            attempted.add(it); true
        }
        assertEquals(emptyList<String>(), remaining)
        assertEquals(listOf("buyer", "seller"), attempted)
    }

    @Test fun `a partial attempt retains only the failed target`() = runTest {
        val remaining = sweep(listOf("buyer", "seller")) { it == "buyer" }
        assertEquals(listOf("seller"), remaining)
    }

    @Test fun `an all-fail attempt retains every target`() = runTest {
        // The data-loss regression: "nothing delivered" must never look like
        // "nothing left to deliver".
        val remaining = sweep(listOf("buyer", "seller")) { false }
        assertEquals(listOf("buyer", "seller"), remaining)
    }

    @Test fun `each target is attempted exactly once, in order`() = runTest {
        val attempted = mutableListOf<String>()
        sweep(listOf("a", "b", "c")) { attempted.add(it); it != "b" }
        assertEquals(listOf("a", "b", "c"), attempted)
    }

    @Test fun `an empty target list has nothing to retry`() = runTest {
        assertEquals(emptyList<String>(), sweep(emptyList()) { true })
    }

    @Test fun `a single delivered target leaves nothing to retry (dispute-loop regression)`() = runTest {
        // P2POrchestrator.retryPendingDisputes pre-fix used `filter { sendOk }`
        // and then only dropped the row when the result was EMPTY. For the real
        // single-target case (targets=[arbitrator], send succeeds) the filter
        // returned the target itself, equal to the input, so neither branch ran:
        // the row retried every 60s forever with no log. The shared policy must
        // return empty here so the caller drops the row.
        val remaining = sweep(listOf("arbitrator")) { true }
        assertEquals(emptyList<String>(), remaining)
    }
}
