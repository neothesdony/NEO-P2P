package com.neop2p.data.network

import com.neop2p.data.network.provider.EsploraProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `testnet keeps emzy first and only lists esplora mirrors`() {
        val ids = ExplorerRegistry.forNetwork("testnet", client).map { it.id }
        assertEquals(listOf("mempool.emzy.de", "mempool.space"), ids)
    }

    @Test
    fun `testnet emzy never advertises address capabilities`() {
        // emzy's testnet4 index serves tip + fees but 404s /address (2026-09-17),
        // so the wallet's address scan must skip it entirely.
        val emzy = ExplorerRegistry.forNetwork("testnet", client).first { it.id == "mempool.emzy.de" }
        assertEquals(EsploraProvider.TIP_AND_FEES, emzy.capabilities)
        assertFalse(Capability.ADDRESS_INFO in emzy.capabilities)
        assertFalse(Capability.ADDRESS_TXS in emzy.capabilities)
        assertFalse(Capability.ADDRESS_UTXOS in emzy.capabilities)
    }

    @Test
    fun `testnet address scans resolve to mempool space only`() {
        val capable = ExplorerRegistry.forNetwork("testnet", client)
            .filter { Capability.ADDRESS_INFO in it.capabilities }
            .map { it.id }
        assertEquals(listOf("mempool.space"), capable)
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
