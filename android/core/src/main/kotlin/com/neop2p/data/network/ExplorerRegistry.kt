package com.neop2p.data.network

import com.neop2p.data.network.provider.BitcoinerLiveProvider
import com.neop2p.data.network.provider.BlockchainComProvider
import com.neop2p.data.network.provider.EsploraProvider
import io.ktor.client.HttpClient

/**
 * Builds the ordered explorer provider list for a network.
 *
 * MAINNET order (2026-09-17): mempool.space -> blockstream.info ->
 * mempool.emzy.de -> btcscan.org -> blockchain.com -> [bitcoiner.live].
 * (emzy was moved to 3rd by explicit product decision; note the code comment
 * this replaced had emzy first because Indonesian mobile ISPs reset TLS to
 * mempool.space / blockstream.info — those networks now pay two failures before
 * reaching emzy.)
 *
 * TESTNET (testnet4): emzy first for TIP + FEES (its testnet4 index has no
 * `/address` endpoint — verified 2026-09-17: tip + fees 200, address 404), then
 * mempool.space which serves every capability. Address scans therefore go
 * straight to mempool.space instead of paying a failing emzy round-trip first.
 * Every newly added provider is mainnet-only.
 */
object ExplorerRegistry {

    /**
     * bitcoiner.live data is CC BY-NC-SA (non-commercial), so its provider is
     * registered but DISABLED by default. Flip to `true` only for non-commercial
     * builds; deleting the block below removes the dependency entirely.
     */
    const val BITCOINER_LIVE_ENABLED: Boolean = false

    fun forNetwork(network: String, httpClient: HttpClient): List<ExplorerProvider> =
        if (network == "mainnet") mainnet(httpClient) else testnet4(httpClient)

    private fun mainnet(httpClient: HttpClient): List<ExplorerProvider> = buildList {
        add(EsploraProvider("mempool.space", "https://mempool.space/api", httpClient))
        add(EsploraProvider("blockstream.info", "https://blockstream.info/api", httpClient))
        add(EsploraProvider("mempool.emzy.de", "https://mempool.emzy.de/api", httpClient))
        // btcscan.org is a stripped Esplora: it has no /v1/fees/recommended.
        add(
            EsploraProvider(
                id = "btcscan.org",
                base = "https://btcscan.org/api",
                httpClient = httpClient,
                capabilities = EsploraProvider.ALL_BUT_FEES
            )
        )
        add(BlockchainComProvider(httpClient))
        if (BITCOINER_LIVE_ENABLED) add(BitcoinerLiveProvider(httpClient))
    }

    private fun testnet4(httpClient: HttpClient): List<ExplorerProvider> = listOf(
        // emzy's testnet4 index serves tip + fees only: /address/... returns 404,
        // so it must never be asked for an address scan (2026-09-17).
        EsploraProvider(
            id = "mempool.emzy.de",
            base = "https://mempool.emzy.de/testnet4/api",
            httpClient = httpClient,
            capabilities = EsploraProvider.TIP_AND_FEES
        ),
        EsploraProvider("mempool.space", "https://mempool.space/testnet4/api", httpClient),
    )
}
