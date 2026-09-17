package com.neop2p.data.network

import com.neop2p.data.escrow.ChainMonitor

/**
 * Which data operations a provider can serve. A provider that does not declare
 * a capability is never asked for it, and a declared capability that still
 * fails returns `null` so the facade moves on to the next provider.
 */
enum class Capability {
    TX_INFO,
    TX_OUTPUTS,
    ADDRESS_INFO,
    ADDRESS_TXS,
    ADDRESS_UTXOS,
    FEES,
    TIP,
    BROADCAST,
}

/**
 * A single bitcoin chain-data source (Esplora mirror, or a provider-specific
 * adapter). Implementations MUST be fail-closed: any non-2xx, non-JSON, missing
 * field, or ambiguous parse returns `null` — never a guessed value — because
 * these answers feed escrow funding verification and fee math.
 *
 * Model types are the existing `ChainMonitor` nested classes so every call site
 * (`EscrowService`, `WalletService`, tests) is unchanged.
 */
interface ExplorerProvider {

    /** Stable identifier used for the sticky "last good provider" preference. */
    val id: String

    /** Operations this provider implements; the facade filters on this. */
    val capabilities: Set<Capability>

    suspend fun txInfo(txid: String, tipHeight: Long?): ChainMonitor.TxInfo? = null

    suspend fun txOutputs(txid: String): List<ChainMonitor.TxOutput>? = null

    suspend fun addressInfo(address: String): ChainMonitor.AddressInfo? = null

    suspend fun addressTxs(address: String, limit: Int): List<ChainMonitor.AddressTx>? = null

    suspend fun addressUtxos(address: String): List<ChainMonitor.Utxo>? = null

    suspend fun feeEstimate(): ChainMonitor.FeeEstimate? = null

    suspend fun tipHeight(): Long? = null

    /**
     * Broadcast a raw tx. Returns the resulting txid, or `null` on failure.
     * A provider that cannot confirm the txid it produced must return `null`
     * rather than a success.
     */
    suspend fun broadcast(txHex: String, expectedTxid: String?): String? = null
}
