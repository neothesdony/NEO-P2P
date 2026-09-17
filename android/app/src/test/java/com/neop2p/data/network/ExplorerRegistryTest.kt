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
