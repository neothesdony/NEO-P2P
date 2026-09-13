package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-3 (2026-09-13): the pure recovery decision `releaseFunds` makes when the
 * pre-broadcast payout verdict refuses the stored tx. The retry must never loop
 * and must never rebuild over a payout that already broadcast.
 */
class EscrowReleaseRecoveryTest {

    @Test
    fun `a live payout is never rebuilt over`() {
        assertEquals("REFUSE", EscrowService.releaseGateRecovery("payout_tx", "funding_tx", false))
    }

    @Test
    fun `a poisoned local payout is rebuilt once`() {
        assertEquals("REGENERATE", EscrowService.releaseGateRecovery(null, "funding_tx", false))
        assertEquals("REGENERATE", EscrowService.releaseGateRecovery("", "funding_tx", false))
    }

    @Test
    fun `a second failure refuses`() {
        assertEquals("REFUSE", EscrowService.releaseGateRecovery(null, "funding_tx", true))
    }

    @Test
    fun `no funding tx means nothing to rebuild from`() {
        assertEquals("REFUSE", EscrowService.releaseGateRecovery(null, null, false))
        assertEquals("REFUSE", EscrowService.releaseGateRecovery(null, "", false))
    }
}
