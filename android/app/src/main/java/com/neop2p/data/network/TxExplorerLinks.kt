package com.neop2p.data.network

/**
 * Outbound "view transaction" web links. These are presentation-only: unlike
 * [ExplorerProvider]s they are never queried programmatically, so they are not
 * pinned and not part of the money path.
 *
 * cloverpool.com and btc.com have no public JSON explorer API (their documented
 * API is a mining-pool account API), so they appear here as link-only options.
 */
object TxExplorerLinks {

    fun forNetwork(network: String, txid: String): List<Pair<String, String>> =
        if (network == "mainnet") mainnet(txid) else testnet(txid)

    private fun mainnet(txid: String): List<Pair<String, String>> = listOf(
        "mempool.space" to "https://mempool.space/tx/$txid",
        "blockchain.com" to "https://www.blockchain.com/explorer/transactions/btc/$txid",
        "cloverpool.com" to "https://explorer.cloverpool.com/btc/tx/$txid",
        "btc.com" to "https://explorer.btc.com/btc/transaction/$txid",
    )

    private fun testnet(txid: String): List<Pair<String, String>> = listOf(
        "mempool.space" to "https://mempool.space/testnet4/tx/$txid",
    )
}
