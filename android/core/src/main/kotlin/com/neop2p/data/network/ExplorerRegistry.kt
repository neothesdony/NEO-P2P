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
 * TESTNET (testnet4): emzy first, declaring every capability EXCEPT the
 * address index (its testnet4 `/address/...` is 404 — verified 2026-09-17 —
 * while tip, fees, `/tx` and `/outspends` are 200, verified 2026-09-25), then
 * mempool.bitmixlist.org and mempool.space as full mirrors.
 *
 * Order note (2026-09-25): mempool.space's clearnet host does not answer Tor
 * exit traffic, and emzy has no address index, so the address-capable
 * bitmixlist mirror is placed BEFORE mempool.space — otherwise a Tor-enabled
 * testnet4 build had no working provider for address/funding reads and every
 * call stalled for the full Tor timeout and then failed.
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
        // emzy's testnet4 index has no address index: /address/... returns 404
        // (2026-09-17), but tip, fees, /tx and /outspends are served (2026-09-25),
        // so declare every capability except the address ones.
        EsploraProvider(
            id = "mempool.emzy.de",
            base = "https://mempool.emzy.de/testnet4/api",
            httpClient = httpClient,
            capabilities = EsploraProvider.ALL_BUT_ADDRESS
        ),
        // Full-capability testnet4 mirror (address index included) reachable
        // through Tor. Placed BEFORE mempool.space, whose clearnet host does not
        // answer Tor exit traffic (2026-09-25) — without it, testnet4 address
        // scans had no Tor-working provider.
        EsploraProvider("mempool.bitmixlist.org", "https://mempool.bitmixlist.org/testnet4/api", httpClient),
        EsploraProvider("mempool.space", "https://mempool.space/testnet4/api", httpClient),
    )
}
