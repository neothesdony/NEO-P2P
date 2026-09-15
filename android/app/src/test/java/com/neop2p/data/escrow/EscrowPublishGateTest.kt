package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowPublishGateTest {

    private fun sig(status: String = "FUNDED", extra: Map<String, String> = emptyMap()) =
        EscrowPublishGate.signature(status, mapOf("funding_tx_id" to "abc", "funded_amount_sats" to "100") + extra)

    @Test fun `signature is order independent`() {
        val a = EscrowPublishGate.signature("FUNDED", mapOf("b" to "2", "a" to "1"))
        val b = EscrowPublishGate.signature("FUNDED", mapOf("a" to "1", "b" to "2"))
        assertEquals(a, b)
    }

    @Test fun `signature changes when a field changes`() {
        assertTrue(sig() != sig(extra = mapOf("funding_tx_id" to "def")))
    }

    @Test fun `first publish sends`() {
        assertEquals(
            EscrowPublishGate.Decision.SEND,
            EscrowPublishGate.decide(null, sig(), false, EscrowPublishGate.Reason.TRANSITION, 1_000L, false),
        )
    }

    @Test fun `unchanged after success is skipped`() {
        val s = EscrowPublishGate.onSuccess(sig(), false)
        assertEquals(
            EscrowPublishGate.Decision.SKIP_UNCHANGED,
            EscrowPublishGate.decide(s, sig(), false, EscrowPublishGate.Reason.TRANSITION, 1_000L, false),
        )
    }

    @Test fun `changed after success sends`() {
        val s = EscrowPublishGate.onSuccess(sig(), false)
        assertEquals(
            EscrowPublishGate.Decision.SEND,
            EscrowPublishGate.decide(s, sig(extra = mapOf("payout_tx_id" to "x")), false, EscrowPublishGate.Reason.TRANSITION, 1_000L, false),
        )
    }

    @Test fun `terminal already sent never retries`() {
        val s = EscrowPublishGate.onSuccess(sig("RELEASED"), true)
        assertEquals(
            EscrowPublishGate.Decision.SKIP_TERMINAL_DONE,
            EscrowPublishGate.decide(s, sig("RELEASED"), true, EscrowPublishGate.Reason.SWEEP, 999_999L, true),
        )
    }

    @Test fun `failure sets backoff and within window is skipped`() {
        val s = EscrowPublishGate.onFailure(null, sig(), false, 10_000L)
        assertEquals(
            EscrowPublishGate.Decision.SKIP_BACKOFF,
            EscrowPublishGate.decide(s, sig(), false, EscrowPublishGate.Reason.RETRY, s.backoffUntilMs - 1, false),
        )
    }

    @Test fun `after backoff elapsed it retries`() {
        val s = EscrowPublishGate.onFailure(null, sig(), false, 10_000L)
        assertEquals(
            EscrowPublishGate.Decision.SEND,
            EscrowPublishGate.decide(s, sig(), false, EscrowPublishGate.Reason.RETRY, s.backoffUntilMs + 1, false),
        )
    }

    @Test fun `resume publishes once even when unchanged`() {
        val s = EscrowPublishGate.onSuccess(sig(), false)
        assertEquals(
            EscrowPublishGate.Decision.SEND,
            EscrowPublishGate.decide(s, sig(), false, EscrowPublishGate.Reason.RESUME, 1_000L, resumeAlreadyDone = false),
        )
        assertEquals(
            EscrowPublishGate.Decision.SKIP_UNCHANGED,
            EscrowPublishGate.decide(s, sig(), false, EscrowPublishGate.Reason.RESUME, 1_000L, resumeAlreadyDone = true),
        )
    }

    @Test fun `success resets failures`() {
        val s = EscrowPublishGate.onSuccess(sig(), false)
        assertEquals(0, s.failures)
        assertEquals(0L, s.backoffUntilMs)
    }

    @Test fun `terminal gives up after max failures`() {
        var s: EscrowPublishGate.State? = null
        repeat(EscrowPublishGate.MAX_FAILURES) { s = EscrowPublishGate.onFailure(s, sig("RELEASED"), true, 1_000L) }
        assertEquals(true, s!!.terminalSent)
    }

    @Test fun `backoff grows exponentially and is capped`() {
        assertEquals(15_000L, EscrowPublishGate.nextBackoffMs(1))
        assertEquals(30_000L, EscrowPublishGate.nextBackoffMs(2))
        assertEquals(EscrowPublishGate.MAX_BACKOFF_MS, EscrowPublishGate.nextBackoffMs(20))
    }
}
