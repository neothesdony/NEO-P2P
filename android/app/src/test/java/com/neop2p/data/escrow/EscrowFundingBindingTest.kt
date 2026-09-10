package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowFundingBindingTest {

    private val outputs = listOf(
        ChainMonitor.TxOutput("tb1qchange", 5000L, 0),
        ChainMonitor.TxOutput("tb1qescrow", 123000L, 1)
    )

    @Test
    fun `finds the vout paying escrow address with exact amount`() {
        assertEquals(1, EscrowService.findFundingOutput(outputs, "tb1qescrow", 123000L))
    }

    @Test
    fun `null when address mismatch`() {
        assertNull(EscrowService.findFundingOutput(outputs, "tb1qother", 123000L))
    }

    @Test
    fun `null when amount mismatch`() {
        assertNull(EscrowService.findFundingOutput(outputs, "tb1qescrow", 999L))
    }

    @Test
    fun `null on empty outputs`() {
        assertNull(EscrowService.findFundingOutput(emptyList(), "tb1qescrow", 123000L))
    }

    @Test
    fun `null when address is null`() {
        assertNull(EscrowService.findFundingOutput(outputs, null, 123000L))
    }

    // Regression (2026-09-01): funding recovery accepted ANY historical tx
    // paying the escrow address the deposit amount — escrow addresses are
    // deterministic (derived from the 2-of-3 keys), so a deposit from a
    // PREVIOUS escrow between the same peers was re-bound to the new escrow
    // and promoted it to FUNDED without a fresh deposit. A funding tx mined
    // before the escrow was created must never bind.
    @Test
    fun `funding tx mined before escrow creation is stale`() {
        val escrowCreatedAt = 1_788_198_000_000L
        // Mined ~35h before the escrow existed (the live bug: old deposit).
        val staleBlockTimeSec = (escrowCreatedAt / 1000) - 125_000
        assertTrue(
            EscrowService.fundingTxIsStale(
                blockTimeSec = staleBlockTimeSec,
                confirmed = true,
                escrowCreatedAt = escrowCreatedAt
            )
        )
    }

    @Test
    fun `funding tx after escrow creation is fresh`() {
        val escrowCreatedAt = 1_788_198_000_000L
        val freshBlockTimeSec = (escrowCreatedAt / 1000) + 60
        assertFalse(
            EscrowService.fundingTxIsStale(
                blockTimeSec = freshBlockTimeSec,
                confirmed = true,
                escrowCreatedAt = escrowCreatedAt
            )
        )
    }

    @Test
    fun `unconfirmed funding tx is never stale on block time`() {
        val escrowCreatedAt = 1_788_198_000_000L
        // block_time unknown (0) while unconfirmed — the mempool broadcast
        // happened after creation; nothing proves it is old.
        assertFalse(
            EscrowService.fundingTxIsStale(
                blockTimeSec = 0L,
                confirmed = false,
                escrowCreatedAt = escrowCreatedAt
            )
        )
    }

    @Test
    fun `unknown block time falls back to escrow creation`() {
        val escrowCreatedAt = 1_788_198_000_000L
        // Mempool/Esplora omits status.block_time on some responses; a
        // confirmed tx without a block time is bound to the escrow's own
        // creation time — the sweep's "promote if a deposit reappears" path
        // must not fail closed on missing metadata.
        assertFalse(
            EscrowService.fundingTxIsStale(
                blockTimeSec = 0L,
                confirmed = true,
                escrowCreatedAt = escrowCreatedAt
            )
        )
    }
}
