package com.neop2p.data.network.provider

import com.neop2p.data.escrow.ChainMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockchainComProviderTest {

    @Test
    fun `confirmed rawtx derives depth from tip`() {
        val json = """{"hash":"tx1","block_height":170,"time":1231731025,"out":[]}"""
        val info = BlockchainComProvider.parseTxInfo(json, tipHeight = 175)
        assertTrue(info!!.confirmed)
        assertEquals(6L, info.confirmations)
        assertEquals("tx1", info.txid)
        assertEquals(1231731025L, info.blockTimeSec)
    }

    @Test
    fun `confirmed without tip falls back to one confirmation`() {
        val json = """{"hash":"tx1","block_height":170,"time":0}"""
        val info = BlockchainComProvider.parseTxInfo(json, tipHeight = null)!!
        assertTrue(info.confirmed)
        assertEquals(1L, info.confirmations)
    }

    @Test
    fun `missing block_height is unconfirmed`() {
        val json = """{"hash":"tx1","time":0}"""
        val info = BlockchainComProvider.parseTxInfo(json, tipHeight = 175)!!
        assertFalse(info.confirmed)
        assertEquals(0L, info.confirmations)
    }

    @Test
    fun `malformed json is null`() {
        assertNull(BlockchainComProvider.parseTxInfo("not json", tipHeight = 1))
        assertNull(BlockchainComProvider.parseTxInfo("""{"time":0}""", tipHeight = 1))
    }

    @Test
    fun `outputs carry addr value and index`() {
        val json = """{"out":[{"addr":"bc1qchange","value":5000,"n":0},
                      {"addr":"bc1qescrow","value":123000,"n":1}]}"""
        val outs = BlockchainComProvider.parseTxOutputs(json)!!
        assertEquals(2, outs.size)
        assertEquals(ChainMonitor.TxOutput("bc1qescrow", 123000L, 1), outs[1])
    }

    @Test
    fun `missing out array degrades to empty`() {
        assertEquals(0, BlockchainComProvider.parseTxOutputs("""{"hash":"x"}""")!!.size)
    }

    @Test
    fun `tip height parses and rejects junk`() {
        assertEquals(967381L, BlockchainComProvider.parseTipHeight("""{"height":967381}"""))
        assertNull(BlockchainComProvider.parseTipHeight("not json"))
    }

    @Test
    fun `broadcast success detection`() {
        assertTrue(BlockchainComProvider.broadcastSucceeded("Transaction Submitted"))
        assertFalse(BlockchainComProvider.broadcastSucceeded("<html>error</html>"))
    }
}
