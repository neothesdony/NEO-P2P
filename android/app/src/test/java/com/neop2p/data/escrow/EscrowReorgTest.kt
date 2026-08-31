package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E7 (2026-09-01): reorg safety for FUNDED escrows.
 *
 * FUNDED is one-shot: onEscrowFunded verifies the funding tx once and never
 * re-checks it. A chain reorg can un-confirm (or drop) the funding tx after
 * FUNDED was set — the 12h+48h auto-refund would then broadcast a tx
 * spending an output that no longer exists. The sweep must re-verify the
 * funding tx before auto-refunding and revert to FUNDING when the deposit
 * is truly gone (unconfirmed AND no address balance), so the existing
 * FUNDING machinery re-verifies (promote if a deposit reappears, cancel
 * after the funding timeout if not).
 *
 * Mirrors the E7 guard in expireStaleEscrows (FUNDED/SIGNED branch) — the
 * sweep itself is Android/Room/bitcoinj-dependent, so the DECISION logic is
 * verified here against the production function.
 */
class EscrowReorgTest {

    /** Mirrors the sweep's E7 action for a FUNDED/SIGNED escrow. */
    private fun sweepAction(txInfo: ChainMonitor.TxInfo?, addressHasBalance: Boolean): String {
        // Explorer failure → fail closed: skip the refund, never revert on
        // uncertainty (a transient API error must not flip a funded escrow).
        if (txInfo == null) return "SKIP"
        // E7: funding tx no longer confirmed AND deposit gone → revert to
        // FUNDING so the sweep re-verifies / cancels instead of refunding.
        if (EscrowService.fundingDepositGone(txInfo.confirmed, addressHasBalance)) return "REVERT"
        return "REFUND"
    }

    @Test
    fun `confirmed funding tx proceeds to refund`() {
        val confirmed = ChainMonitor.TxInfo("txid", confirmed = true, confirmations = 3)
        assertEquals("REFUND", sweepAction(confirmed, addressHasBalance = true))
        // Confirmed but the address reports no balance (explorer lag): the tx
        // is confirmed — refund is still the correct action.
        assertEquals("REFUND", sweepAction(confirmed, addressHasBalance = false))
    }

    @Test
    fun `unconfirmed but deposit still in mempool keeps funded`() {
        // Reorged out of a block but still in mempool: the deposit is not
        // gone — a refund spending it is a valid child tx. Keep FUNDED.
        val mempool = ChainMonitor.TxInfo("txid", confirmed = false, confirmations = 0)
        assertEquals("REFUND", sweepAction(mempool, addressHasBalance = true))
        assertFalse(EscrowService.fundingDepositGone(confirmed = false, addressHasBalance = true))
    }

    @Test
    fun `unconfirmed and deposit gone reverts to funding instead of refunding`() {
        // The dangerous case: the funding tx was dropped by a reorg and the
        // address holds nothing. Auto-refunding would broadcast a tx spending
        // a nonexistent output. Must revert to FUNDING.
        val gone = ChainMonitor.TxInfo("txid", confirmed = false, confirmations = 0)
        assertEquals("REVERT", sweepAction(gone, addressHasBalance = false))
        assertTrue(EscrowService.fundingDepositGone(confirmed = false, addressHasBalance = false))
    }

    @Test
    fun `explorer failure fails closed - no refund no revert`() {
        // txInfo == null (explorer unreachable): skip the refund this sweep
        // AND do not revert — a transient API error must not flip a funded
        // escrow's status.
        assertEquals("SKIP", sweepAction(null, addressHasBalance = false))
        assertEquals("SKIP", sweepAction(null, addressHasBalance = true))
    }

    @Test
    fun `funding deposit gone is false whenever the tx is confirmed`() {
        // The revert decision is only about the gone case; a confirmed tx is
        // never "gone" regardless of the address balance check.
        assertFalse(EscrowService.fundingDepositGone(confirmed = true, addressHasBalance = false))
        assertFalse(EscrowService.fundingDepositGone(confirmed = true, addressHasBalance = true))
    }
}
