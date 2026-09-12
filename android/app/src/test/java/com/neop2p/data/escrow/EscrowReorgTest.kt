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
 * FUNDED was set — the 2h+2h auto-refund would then broadcast a tx
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
    private fun sweepAction(txInfo: ChainMonitor.TxInfo?, addressHasBalance: Boolean, required: Int = 1): String =
        EscrowService.fundingRefundDecision(txInfo, addressHasBalance, required)

    @Test
    fun `confirmed funding tx proceeds to refund`() {
        val confirmed = ChainMonitor.TxInfo("txid", confirmed = true, confirmations = 3, blockTimeSec = 0L)
        assertEquals("REFUND", sweepAction(confirmed, addressHasBalance = true))
        // Confirmed but the address reports no balance (explorer lag): the tx
        // is confirmed — refund is still the correct action.
        assertEquals("REFUND", sweepAction(confirmed, addressHasBalance = false))
    }

    @Test
    fun `unconfirmed but deposit still in mempool keeps funded`() {
        // Reorged out of a block but still in mempool: the deposit is not
        // gone — a refund spending it is a valid child tx. Keep FUNDED.
        val mempool = ChainMonitor.TxInfo("txid", confirmed = false, confirmations = 0, blockTimeSec = 0L)
        assertEquals("REFUND", sweepAction(mempool, addressHasBalance = true))
        assertFalse(EscrowService.fundingDepositGone(confirmed = false, addressHasBalance = true))
    }

    @Test
    fun `unconfirmed and deposit gone reverts to funding instead of refunding`() {
        // The dangerous case: the funding tx was dropped by a reorg and the
        // address holds nothing. Auto-refunding would broadcast a tx spending
        // a nonexistent output. Must revert to FUNDING.
        val gone = ChainMonitor.TxInfo("txid", confirmed = false, confirmations = 0, blockTimeSec = 0L)
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
        assertEquals("SKIP", sweepAction(null, addressHasBalance = true, required = 3))
    }

    @Test
    fun `funding deposit gone is false whenever the tx is confirmed`() {
        // The revert decision is only about the gone case; a confirmed tx is
        // never "gone" regardless of the address balance check.
        assertFalse(EscrowService.fundingDepositGone(confirmed = true, addressHasBalance = false))
        assertFalse(EscrowService.fundingDepositGone(confirmed = true, addressHasBalance = true))
    }

    // ── E4 (2026-09-01): reorg shaved the depth below required_confirmations ──

    @Test
    fun `confirmed but depth below required reverts instead of refunding`() {
        // The E4 case: the funding tx is still confirmed AND the address still
        // holds the deposit (so the E7 gone-test passes), but a reorg shaved
        // the depth below the escrow's required confirmations. Refunding would
        // spend an input the escrow gate would never have accepted — revert to
        // FUNDING so the machinery re-verifies.
        val shallow = ChainMonitor.TxInfo("txid", confirmed = true, confirmations = 1, blockTimeSec = 0L)
        assertEquals("REVERT", sweepAction(shallow, addressHasBalance = true, required = 3))
    }

    @Test
    fun `depth at or above required refunds`() {
        val at = ChainMonitor.TxInfo("txid", confirmed = true, confirmations = 3, blockTimeSec = 0L)
        assertEquals("REFUND", sweepAction(at, addressHasBalance = true, required = 3))
        val above = ChainMonitor.TxInfo("txid", confirmed = true, confirmations = 12, blockTimeSec = 0L)
        assertEquals("REFUND", sweepAction(above, addressHasBalance = true, required = 3))
    }

    @Test
    fun `default required confirmations of 1 refunds at depth 1`() {
        // Default gate: depth 1 satisfies required=1 (the tip fetch is
        // best-effort and can report 1). Must not revert.
        val depth1 = ChainMonitor.TxInfo("txid", confirmed = true, confirmations = 1, blockTimeSec = 0L)
        assertEquals("REFUND", sweepAction(depth1, addressHasBalance = true))
    }

    @Test
    fun `unconfirmed with no balance and depth check both revert`() {
        // E7 (gone) takes precedence — both conditions revert.
        val gone = ChainMonitor.TxInfo("txid", confirmed = false, confirmations = 0, blockTimeSec = 0L)
        assertEquals("REVERT", sweepAction(gone, addressHasBalance = false, required = 3))
    }

    @Test
    fun `funding depth below required is false for unconfirmed or sufficient`() {
        assertFalse(EscrowService.fundingDepthBelowRequired(confirmed = false, confirmations = 0, requiredConfirmations = 3))
        assertFalse(EscrowService.fundingDepthBelowRequired(confirmed = true, confirmations = 5, requiredConfirmations = 3))
        assertTrue(EscrowService.fundingDepthBelowRequired(confirmed = true, confirmations = 1, requiredConfirmations = 3))
    }

    // ── E8 (2026-09-10): sweep promote path enforces required_confirmations ──

    @Test
    fun `mempool deposit waits instead of promoting`() {
        // The E8 case: the funding tx is bound but still unconfirmed (0
        // confirmations) when the 15-min funding window passes. The sweep
        // must NOT promote to FUNDED — the manual gate would reject it.
        val mempool = ChainMonitor.TxInfo("txid", confirmed = false, confirmations = 0, blockTimeSec = 0L)
        assertEquals("WAIT", EscrowService.fundingPromotionDecision(mempool, requiredConfirmations = 1))
        assertEquals("WAIT", EscrowService.fundingPromotionDecision(mempool, requiredConfirmations = 3))
    }

    @Test
    fun `confirmed at required depth promotes`() {
        val depth1 = ChainMonitor.TxInfo("txid", confirmed = true, confirmations = 1, blockTimeSec = 0L)
        assertEquals("PROMOTE", EscrowService.fundingPromotionDecision(depth1, requiredConfirmations = 1))
        val depth3 = ChainMonitor.TxInfo("txid", confirmed = true, confirmations = 3, blockTimeSec = 0L)
        assertEquals("PROMOTE", EscrowService.fundingPromotionDecision(depth3, requiredConfirmations = 3))
        val depth12 = ChainMonitor.TxInfo("txid", confirmed = true, confirmations = 12, blockTimeSec = 0L)
        assertEquals("PROMOTE", EscrowService.fundingPromotionDecision(depth12, requiredConfirmations = 1))
    }

    @Test
    fun `confirmed but below required depth waits`() {
        // Confirmed at depth 1 but the escrow requires 3 — keep FUNDING.
        val shallow = ChainMonitor.TxInfo("txid", confirmed = true, confirmations = 1, blockTimeSec = 0L)
        assertEquals("WAIT", EscrowService.fundingPromotionDecision(shallow, requiredConfirmations = 3))
    }

    @Test
    fun `explorer failure skips promotion - fails closed`() {
        // txInfo == null (explorer unreachable): never promote on
        // uncertainty — keep FUNDING and retry next sweep.
        assertEquals("SKIP", EscrowService.fundingPromotionDecision(null, requiredConfirmations = 1))
        assertEquals("SKIP", EscrowService.fundingPromotionDecision(null, requiredConfirmations = 3))
    }
}
