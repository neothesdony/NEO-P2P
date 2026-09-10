package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

class ChainMonitorTxOutputsTest {
    private val sample = """{
        "txid": "aa",
        "vout": [
            {"scriptpubkey_address": "tb1qescrowfunding", "value": 123000},
            {"scriptpubkey_address": "tb1qchange", "value": 5000}
        ]
    }"""

    @Test
    fun `parses outputs with address value and index`() {
        val outputs = ChainMonitor.parseTxOutputs(sample)
        assertEquals(2, outputs.size)
        assertEquals(123000L, outputs[0].valueSats)
        assertEquals("tb1qescrowfunding", outputs[0].scriptPubkeyAddress)
        assertEquals(0, outputs[0].index)
        assertEquals(5000L, outputs[1].valueSats)
        assertEquals(1, outputs[1].index)
    }

    @Test
    fun `missing fields become null zero`() {
        val outputs = ChainMonitor.parseTxOutputs("""{"vout":[{"value":1}]}""")
        assertEquals(1, outputs.size)
        assertEquals(null, outputs[0].scriptPubkeyAddress)
        assertEquals(1L, outputs[0].valueSats)
        assertEquals(0, outputs[0].index)
    }

    @Test
    fun `empty or missing vout returns empty list`() {
        assertEquals(0, ChainMonitor.parseTxOutputs("""{"txid":"x"}""").size)
        assertEquals(0, ChainMonitor.parseTxOutputs("{}").size)
    }
}
