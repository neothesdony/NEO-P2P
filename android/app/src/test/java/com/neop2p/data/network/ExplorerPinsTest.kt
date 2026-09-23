package com.neop2p.data.network

import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerPinsTest {

    private val specs = ExplorerPins.pinSpecs()

    @Test
    fun `every explorer host is pinned`() {
        val hosts = specs.map { it.first }.toSet()
        for (h in listOf("mempool.space", "blockstream.info", "mempool.emzy.de", "btcscan.org", "blockchain.info")) {
            assertTrue("missing explorer pin for $h", h in hosts)
        }
    }

    @Test
    fun `market price hosts are pinned`() {
        val hosts = specs.map { it.first }.toSet()
        assertTrue("missing pin for api.coingecko.com", "api.coingecko.com" in hosts)
        assertTrue("missing pin for api.coinpaprika.com", "api.coinpaprika.com" in hosts)
    }

    @Test
    fun `each host carries a backup pin`() {
        val byHost = specs.groupBy({ it.first }, { it.second })
        for ((host, pins) in byHost) {
            assertTrue("$host has fewer than 2 pins (no backup)", pins.distinct().size >= 2)
        }
    }
}
