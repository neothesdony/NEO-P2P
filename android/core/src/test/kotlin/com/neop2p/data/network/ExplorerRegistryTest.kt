package com.neop2p.data.network

import com.neop2p.data.network.provider.EsploraProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerRegistryTest {

    private val client = HttpClient(OkHttp)

    @Test
    fun `mainnet provider order matches the approved list`() {
        val ids = ExplorerRegistry.forNetwork("mainnet", client).map { it.id }
        assertEquals(
            listOf(
                "mempool.space",
                "blockstream.info",
                "mempool.emzy.de",
                "btcscan.org",
                "blockchain.com",
            ),
            ids
        )
    }

    @Test
    fun `testnet keeps emzy first and lists every esplora mirror`() {
        val ids = ExplorerRegistry.forNetwork("testnet", client).map { it.id }
        assertEquals(listOf("mempool.emzy.de", "mempool.bitmixlist.org", "mempool.space"), ids)
    }

    @Test
    fun `testnet emzy serves tx but never advertises address capabilities`() {
        // emzy's testnet4 index 404s /address (2026-09-17) but serves tip, fees,
        // /tx and /outspends (2026-09-25): declare every capability except the
        // address ones, so the wallet's address scan skips it while funding
        // verification can still use it.
        val emzy = ExplorerRegistry.forNetwork("testnet", client).first { it.id == "mempool.emzy.de" }
        assertEquals(EsploraProvider.ALL_BUT_ADDRESS, emzy.capabilities)
        assertFalse(Capability.ADDRESS_INFO in emzy.capabilities)
        assertFalse(Capability.ADDRESS_TXS in emzy.capabilities)
        assertFalse(Capability.ADDRESS_UTXOS in emzy.capabilities)
        assertTrue(Capability.TX_OUTPUTS in emzy.capabilities)
        assertTrue(Capability.BROADCAST in emzy.capabilities)
    }

    @Test
    fun `testnet address scans prefer a tor-reachable mirror before mempool space`() {
        // 2026-09-25: mempool.space is unreachable through the embedded Tor
        // (stalls for the full Tor timeout, then fails) and emzy has no address
        // index, so an address-capable mirror must precede mempool.space.
        val capable = ExplorerRegistry.forNetwork("testnet", client)
            .filter { Capability.ADDRESS_INFO in it.capabilities }
            .map { it.id }
        assertEquals(listOf("mempool.bitmixlist.org", "mempool.space"), capable)
    }

    @Test
    fun `btcscan advertises every capability except fees`() {
        val btcscan = ExplorerRegistry.forNetwork("mainnet", client).first { it.id == "btcscan.org" }
        assertFalse(Capability.FEES in btcscan.capabilities)
        assertEquals(EsploraProvider.ALL_BUT_FEES, btcscan.capabilities)
    }

    @Test
    fun `bitcoiner live is disabled by default`() {
        assertFalse(ExplorerRegistry.BITCOINER_LIVE_ENABLED)
    }
}
