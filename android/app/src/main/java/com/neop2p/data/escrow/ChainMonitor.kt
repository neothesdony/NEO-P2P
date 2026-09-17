package com.neop2p.data.escrow

import android.util.Log
import com.neop2p.BuildConfig
import com.neop2p.data.network.Capability
import com.neop2p.data.network.ExplorerProvider
import com.neop2p.data.network.ExplorerRegistry
import io.ktor.client.HttpClient
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Chain data facade over an ordered list of [ExplorerProvider]s.
 *
 * Used by EscrowService/WalletService to verify funding transactions, broadcast
 * payout transactions, and estimate on-chain fees. Each public method iterates
 * the providers that declare the needed [Capability], tries them in order with
 * the last provider that answered first ("sticky"), and fails closed when no
 * provider can answer. Public API and return types are unchanged from the
 * pre-provider implementation.
 */
@Singleton
class ChainMonitor(
    private val providers: List<ExplorerProvider>,
) {
    /** Production wiring; tests may pass an explicit provider list. */
    constructor(httpClient: HttpClient) : this(
        ExplorerRegistry.forNetwork(BuildConfig.NETWORK, httpClient)
    )

    companion object {
        private const val TAG = "ChainMonitor"

        /**
         * Pure ordering: the last-known-good [preferred] first, then the rest
         * in configured order. Never drops or duplicates an entry.
         */
        internal fun orderedBases(bases: List<String>, preferred: String?): List<String> {
            if (preferred == null || preferred !in bases) return bases
            return listOf(preferred) + bases.filter { it != preferred }
        }

        /**
         * Pure parser for Mempool/Esplora `/tx/{txid}` confirmation info.
         *
         * The API does NOT return a `confirmations` field — only
         * `status.confirmed` + `status.block_height`. Confirmations are derived
         * from the tip height: `tip - block_height + 1`. When the tip is unknown
         * but the tx IS confirmed, fall back to 1 (satisfies the default
         * required_confirmations=1 gate; deeper requirements fail closed).
         */
        fun parseTxInfo(json: String, tipHeight: Long?): TxInfo {
            val obj = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                return TxInfo("", false, 0L, 0L)
            }
            val txid = obj["txid"]?.jsonPrimitive?.content ?: ""
            val status = obj["status"]?.jsonObject
            val confirmed = status?.get("confirmed")?.jsonPrimitive?.content
                ?.toBooleanStrictOrNull() ?: false
            val blockHeight = status?.get("block_height")?.jsonPrimitive?.content?.toLongOrNull()
            val blockTime = status?.get("block_time")?.jsonPrimitive?.content?.toLongOrNull()
            val confirmations = when {
                !confirmed -> 0L
                blockHeight != null && tipHeight != null && tipHeight >= blockHeight ->
                    tipHeight - blockHeight + 1
                else -> 1L
            }
            return TxInfo(txid, confirmed, confirmations, blockTime ?: 0L)
        }

        /**
         * Pure parser for Mempool/Esplora `/tx/{txid}` vout JSON. Missing
         * fields degrade to null/0, never throw.
         */
        fun parseTxOutputs(json: String): List<TxOutput> {
            val obj = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                return emptyList()
            }
            val vout = obj["vout"]?.jsonArray ?: return emptyList()
            return vout.mapIndexed { i, el ->
                val o = el.jsonObject
                TxOutput(
                    scriptPubkeyAddress = o["scriptpubkey_address"]?.jsonPrimitive?.content,
                    valueSats = o["value"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    index = i
                )
            }
        }

        /**
         * A broadcast response is an acceptance only when it is a 64-hex txid —
         * and, when we know the tx we built, only when it IS that txid.
         * Mempool/Esplora return the txid as PLAIN TEXT from POST /tx.
         */
        fun broadcastAccepted(body: String, expectedTxid: String?): Boolean {
            val trimmed = body.trim()
            if (!trimmed.matches(Regex("[0-9a-fA-F]{64}"))) return false
            return expectedTxid == null || trimmed.equals(expectedTxid, ignoreCase = true)
        }

        /**
         * Reconciliation verdict when every explorer base failed to answer. A tx
         * the chain already knows under OUR txid was broadcast — reporting
         * "send failed" there invites a double spend.
         */
        fun reconciledAfterFailure(info: TxInfo?, expectedTxid: String): Boolean =
            info != null && info.txid.equals(expectedTxid, ignoreCase = true)
    }

    /**
     * Last provider that answered, tried FIRST on the next call. In-memory for
     * the process lifetime: the app runs a long-lived foreground service, so
     * this removes the per-call reset penalty on a blocked network.
     */
    @Volatile private var preferredProviderId: String? = null

    /** Providers supporting [cap], sticky preferred first, order otherwise stable. */
    private fun capable(cap: Capability): List<ExplorerProvider> {
        val supported = providers.filter { cap in it.capabilities }
        val preferred = preferredProviderId ?: return supported
        if (supported.none { it.id == preferred }) return supported
        return supported.sortedByDescending { it.id == preferred }
    }

    /**
     * Get recommended fee rates in sat/vB.
     * Returns (fastest, halfHour, hour) or defaults if every provider fails.
     */
    suspend fun estimateFees(): FeeEstimate {
        for (provider in capable(Capability.FEES)) {
            val e = runCatching { provider.feeEstimate() }.getOrNull() ?: continue
            preferredProviderId = provider.id
            // The rate is a third-party input that feeds money math; clamp at
            // this single choke point and normalize monotonic (a MEDIUM send
            // must never cost more than a FAST one).
            return com.neop2p.data.wallet.WalletFeePolicy.normalizeMonotonic(
                FeeEstimate(
                    fastest = com.neop2p.data.wallet.WalletFeePolicy.clampRate(e.fastest),
                    halfHour = com.neop2p.data.wallet.WalletFeePolicy.clampRate(e.halfHour),
                    hour = com.neop2p.data.wallet.WalletFeePolicy.clampRate(e.hour)
                )
            )
        }
        Log.w(TAG, "Fee estimation failed on all providers, using defaults")
        return FeeEstimate(50L, 30L, 20L)
    }

    /**
     * Broadcast a raw transaction hex to the Bitcoin network.
     *
     * @param expectedTxid the txid of the tx we built. When non-null the
     *   response must equal it, and a total broadcast failure is reconciled
     *   against the chain before it is reported as a failure — a tx another
     *   explorer already accepted must never surface as "send failed".
     */
    suspend fun broadcastTx(txHex: String, expectedTxid: String? = null): Result<String> {
        for (provider in capable(Capability.BROADCAST)) {
            val response = runCatching { provider.broadcast(txHex, expectedTxid) }.getOrNull()
                ?: continue
            if (!broadcastAccepted(response, expectedTxid)) continue
            preferredProviderId = provider.id
            Log.i(TAG, "Transaction broadcast: ${response.trim()}")
            return Result.success(response.trim())
        }
        if (expectedTxid != null) {
            val info = getTxInfo(expectedTxid).getOrNull()
            if (reconciledAfterFailure(info, expectedTxid)) {
                Log.i(TAG, "Broadcast reported failure but $expectedTxid is known to the chain — treating as sent")
                return Result.success(expectedTxid)
            }
        }
        Log.e(TAG, "Broadcast failed on all providers")
        return Result.failure(IllegalStateException("Broadcast failed on all providers"))
    }

    /**
     * The current chain tip height, or null when unavailable. Never returns a
     * stale height: on failure the caller must treat the tip as unknown.
     */
    suspend fun tipHeight(): Long? {
        for (provider in capable(Capability.TIP)) {
            val height = runCatching { provider.tipHeight() }.getOrNull() ?: continue
            preferredProviderId = provider.id
            return height
        }
        return null
    }

    /**
     * Get transaction details (confirmations, outputs). The tip fetch is
     * best-effort: when it fails, a confirmed tx reports 1 confirmation
     * (satisfies the default required_confirmations=1 gate).
     */
    suspend fun getTxInfo(txid: String): Result<TxInfo> {
        val tip = tipHeight()
        for (provider in capable(Capability.TX_INFO)) {
            val info = runCatching { provider.txInfo(txid, tip) }.getOrNull() ?: continue
            preferredProviderId = provider.id
            return Result.success(info)
        }
        Log.e(TAG, "Failed to get tx info from all providers: $txid")
        return Result.failure(IllegalStateException("No provider returned tx info for $txid"))
    }

    /**
     * Get transaction outputs (vout array) for a txid. Used to verify that a
     * funding transaction actually pays the escrow address the expected deposit,
     * and to derive the real funding output index.
     */
    suspend fun getTxOutputs(txid: String): Result<List<TxOutput>> {
        for (provider in capable(Capability.TX_OUTPUTS)) {
            val outputs = runCatching { provider.txOutputs(txid) }.getOrNull() ?: continue
            preferredProviderId = provider.id
            return Result.success(outputs)
        }
        Log.e(TAG, "Failed to get tx outputs from all providers: $txid")
        return Result.failure(IllegalStateException("No provider returned outputs for $txid"))
    }

    /** Get address balance (confirmed + unconfirmed). */
    suspend fun getAddressInfo(address: String): Result<AddressInfo> {
        for (provider in capable(Capability.ADDRESS_INFO)) {
            val info = runCatching { provider.addressInfo(address) }.getOrNull() ?: continue
            preferredProviderId = provider.id
            return Result.success(info)
        }
        Log.e(TAG, "Failed to get address info for ${address.take(8)}…")
        return Result.failure(IllegalStateException("No provider returned address info"))
    }

    /**
     * Get recent transactions for an address (newest first). Default cap is 50;
     * this still reads one Esplora page per provider.
     */
    suspend fun getAddressTxs(address: String, limit: Int = 50): Result<List<AddressTx>> {
        for (provider in capable(Capability.ADDRESS_TXS)) {
            val txs = runCatching { provider.addressTxs(address, limit) }.getOrNull() ?: continue
            preferredProviderId = provider.id
            return Result.success(txs)
        }
        Log.e(TAG, "Failed to get address txs for ${address.take(8)}…")
        return Result.failure(IllegalStateException("No provider returned address txs"))
    }

    /** Get spendable UTXOs for an address (confirmed only). */
    suspend fun getAddressUtxos(address: String): Result<List<Utxo>> {
        for (provider in capable(Capability.ADDRESS_UTXOS)) {
            val utxos = runCatching { provider.addressUtxos(address) }.getOrNull() ?: continue
            preferredProviderId = provider.id
            return Result.success(utxos)
        }
        Log.e(TAG, "Failed to get utxos for ${address.take(8)}…")
        return Result.failure(IllegalStateException("No provider returned utxos"))
    }

    data class TxOutput(
        val scriptPubkeyAddress: String?,
        val valueSats: Long,
        val index: Int
    )

    data class AddressInfo(
        val confirmedBalanceSats: Long,
        val unconfirmedBalanceSats: Long,
        /** chain + mempool tx count; 0 means the address was never used. */
        val txCount: Long = 0L
    ) {
        val totalSats: Long get() = confirmedBalanceSats + unconfirmedBalanceSats
    }

    data class AddressTx(
        val txid: String,
        val confirmed: Boolean,
        val blockTimeSec: Long,
        val feeSats: Long,
        val receivedSats: Long,
        val spentSats: Long,
        val netSats: Long,
        val direction: TxDirection
    )

    enum class TxDirection { RECEIVE, SEND, SELF }

    data class Utxo(
        val txid: String,
        val vout: Int,
        val valueSats: Long
    )

    data class FeeEstimate(
        val fastest: Long,   // sat/vB, next block
        val halfHour: Long,  // ~3 blocks
        val hour: Long       // ~6 blocks
    )

    data class TxInfo(
        val txid: String,
        val confirmed: Boolean,
        val confirmations: Long,
        /** Unix seconds of the mining block (0 when unconfirmed or missing). */
        val blockTimeSec: Long
    )
}
