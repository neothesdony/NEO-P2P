package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for ChainMonitor.parseTxInfo — the confirmation-depth
 * parser for Mempool/Esplora `/tx/{txid}` responses.
 *
 * The API does NOT return a `confirmations` field (verified live against
 * mempool.space and mempool.emzy.de, 2026-08-28): only `status.confirmed`
 * and `status.block_height`. Depth is derived from the tip height; when the
 * tip is unknown a confirmed tx reports 1 (satisfies the default
 * required_confirmations=1 gate).
 */
class ChainMonitorTxInfoTest {

    private fun txJson(confirmed: Boolean, blockHeight: Long? = null): String {
        val status = buildString {
            append("\"confirmed\":").append(confirmed)
            if (blockHeight != null) append(",\"block_height\":").append(blockHeight)
        }
        return """{"txid":"abc123","status":{$status}}"""
    }

    @Test
    fun `confirmed tx with tip derives exact depth`() {
        val info = ChainMonitor.parseTxInfo(txJson(confirmed = true, blockHeight = 100), tipHeight = 105)
        assertTrue(info.confirmed)
        assertEquals(6L, info.confirmations) // 105 - 100 + 1
    }

    @Test
    fun `confirmed tx at tip has one confirmation`() {
        val info = ChainMonitor.parseTxInfo(txJson(confirmed = true, blockHeight = 105), tipHeight = 105)
        assertEquals(1L, info.confirmations)
    }

    @Test
    fun `confirmed tx without tip falls back to one`() {
        val info = ChainMonitor.parseTxInfo(txJson(confirmed = true, blockHeight = 100), tipHeight = null)
        assertEquals(1L, info.confirmations)
    }

    @Test
    fun `unconfirmed tx has zero confirmations`() {
        val info = ChainMonitor.parseTxInfo(txJson(confirmed = false), tipHeight = 105)
        assertFalse(info.confirmed)
        assertEquals(0L, info.confirmations)
    }

    @Test
    fun `malformed json degrades to unconfirmed zero`() {
        val info = ChainMonitor.parseTxInfo("not json", tipHeight = 105)
        assertFalse(info.confirmed)
        assertEquals(0L, info.confirmations)
    }

    @Test
    fun `confirmed tx carries block time`() {
        val json = """{"txid":"abc123","status":{"confirmed":true,"block_height":100,"block_time":1788068694}}"""
        val info = ChainMonitor.parseTxInfo(json, tipHeight = 105)
        assertTrue(info.confirmed)
        assertEquals(1788068694L, info.blockTimeSec)
    }

    @Test
    fun `unconfirmed tx block time is zero`() {
        val info = ChainMonitor.parseTxInfo(txJson(confirmed = false), tipHeight = 105)
        assertFalse(info.confirmed)
        assertEquals(0L, info.blockTimeSec)
    }

    @Test
    fun `missing status degrades to unconfirmed zero`() {
        val info = ChainMonitor.parseTxInfo("""{"txid":"abc"}""", tipHeight = 105)
        assertFalse(info.confirmed)
        assertEquals(0L, info.confirmations)
    }
}
