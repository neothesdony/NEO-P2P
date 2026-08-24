package com.neop2p.data.escrow

import android.util.Log
import com.neop2p.BuildConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors the Bitcoin blockchain via public APIs (Mempool.space).
 *
 * Used by EscrowService to:
 * - Verify funding transactions
 * - Broadcast payout transactions
 * - Estimate on-chain fees
 */
@Singleton
class ChainMonitor @Inject constructor(
    private val httpClient: HttpClient
) {
    companion object {
        private const val MEMPOOL_BASE_MAINNET = "https://mempool.space/api"
        private const val MEMPOOL_BASE_TESTNET = "https://mempool.space/testnet/api"
        // Blockstream.info mirrors the Mempool JSON API 1:1 and is reachable
        // from networks where mempool.space times out (observed 2026-08-24).
        private const val BLOCKSTREAM_BASE_MAINNET = "https://blockstream.info/api"
        private const val BLOCKSTREAM_BASE_TESTNET = "https://blockstream.info/testnet/api"
        private const val TAG = "ChainMonitor"

        /** Use the testnet Mempool endpoint when the app runs on testnet. */
        private val MEMPOOL_BASE: String =
            if (BuildConfig.NETWORK == "mainnet") MEMPOOL_BASE_MAINNET else MEMPOOL_BASE_TESTNET

        private val BLOCKSTREAM_BASE: String =
            if (BuildConfig.NETWORK == "mainnet") BLOCKSTREAM_BASE_MAINNET else BLOCKSTREAM_BASE_TESTNET
    }

    /**
     * GET from Mempool, falling back to Blockstream.info on failure (timeout,
     * DNS, geo-block). Both expose the same JSON shapes.
     */
    private suspend fun apiGet(path: String): String {
        val mempool = try {
            httpClient.get("$MEMPOOL_BASE$path").bodyAsText()
        } catch (e: Exception) {
            Log.w(TAG, "Mempool GET $path failed (${e.message}), falling back to Blockstream")
            httpClient.get("$BLOCKSTREAM_BASE$path").bodyAsText()
        }
        return mempool
    }

    /**
     * POST a raw tx to Mempool, falling back to Blockstream on failure.
     */
    private suspend fun apiPost(path: String, body: String): String {
        val mempool = try {
            httpClient.post("$MEMPOOL_BASE$path") {
                setBody(body)
                contentType(ContentType.Text.Plain)
            }.bodyAsText()
        } catch (e: Exception) {
            Log.w(TAG, "Mempool POST $path failed (${e.message}), falling back to Blockstream")
            httpClient.post("$BLOCKSTREAM_BASE$path") {
                setBody(body)
                contentType(ContentType.Text.Plain)
            }.bodyAsText()
        }
        return mempool
    }

    /**
     * Get recommended fee rates in sat/vB.
     * Returns (fastest, halfHour, hour) or defaults if API fails.
     */
    suspend fun estimateFees(): FeeEstimate {
        return try {
            val json = Json.parseToJsonElement(apiGet("/v1/fees/recommended")).jsonObject
            FeeEstimate(
                fastest = json["fastestFee"]?.jsonPrimitive?.content?.toLongOrNull() ?: 50L,
                halfHour = json["halfHourFee"]?.jsonPrimitive?.content?.toLongOrNull() ?: 30L,
                hour = json["hourFee"]?.jsonPrimitive?.content?.toLongOrNull() ?: 20L
            )
        } catch (e: Exception) {
            Log.w(TAG, "Fee estimation failed, using defaults: ${e.message}")
            FeeEstimate(50L, 30L, 20L)
        }
    }

    /**
     * Broadcast a raw transaction hex to the Bitcoin network.
     * Returns the txid on success.
     */
    suspend fun broadcastTx(txHex: String): Result<String> {
        return try {
            val txid = apiPost("/tx", txHex).trim()
            Log.i(TAG, "Transaction broadcast: $txid")
            Result.success(txid)
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Get transaction details (confirmations, outputs).
     */
    suspend fun getTxInfo(txid: String): Result<TxInfo> {
        return try {
            val json = Json.parseToJsonElement(apiGet("/tx/$txid")).jsonObject
            val confirmations = json["confirmations"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val status = json["status"]?.jsonObject
            val confirmed = status?.get("confirmed")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            Result.success(TxInfo(txid, confirmed, confirmations))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get tx info: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Get address balance (confirmed + unconfirmed) from Mempool.
     */
    suspend fun getAddressInfo(address: String): Result<AddressInfo> {
        return try {
            val json = Json.parseToJsonElement(apiGet("/address/$address")).jsonObject
            val stats = json["chain_stats"]?.jsonObject
            val mempool = json["mempool_stats"]?.jsonObject
            val confirmed = stats?.get("funded_txo_sum")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val spent = stats?.get("spent_txo_sum")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val unconfirmed = mempool?.get("funded_txo_sum")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val unconfirmedSpent = mempool?.get("spent_txo_sum")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            Result.success(
                AddressInfo(
                    confirmedBalanceSats = confirmed - spent,
                    unconfirmedBalanceSats = unconfirmed - unconfirmedSpent
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get address info for $address", e)
            Result.failure(e)
        }
    }

    /**
     * Get recent transactions for an address (newest first).
     */
    suspend fun getAddressTxs(address: String, limit: Int = 10): Result<List<AddressTx>> {
        return try {
            val arr = Json.parseToJsonElement(apiGet("/address/$address/txs")).jsonArray
            val txs = arr.take(limit).mapNotNull { el ->
                val obj = el.jsonObject
                val txid = obj["txid"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val status = obj["status"]?.jsonObject
                val confirmed = status?.get("confirmed")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val blockTime = status?.get("block_time")?.jsonPrimitive?.content?.toLongOrNull()
                val fee = obj["fee"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val vout = obj["vout"]?.jsonArray.orEmpty()
                val totalOut = vout.sumOf { it.jsonObject["value"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L }
                val vin = obj["vin"]?.jsonArray.orEmpty()
                val receivedSats = vout.sumOf { ov ->
                    val o = ov.jsonObject
                    if (o["scriptpubkey_address"]?.jsonPrimitive?.content == address)
                        o["value"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L else 0L
                }
                val spentSats = vin.sumOf { vi ->
                    val prevout = vi.jsonObject["prevout"]?.jsonObject
                    if (prevout?.get("scriptpubkey_address")?.jsonPrimitive?.content == address)
                        prevout["value"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L else 0L
                }
                val direction = when {
                    spentSats == 0L -> TxDirection.RECEIVE
                    receivedSats < spentSats -> TxDirection.SEND
                    else -> TxDirection.SELF
                }
                AddressTx(
                    txid = txid,
                    confirmed = confirmed,
                    blockTimeSec = blockTime ?: System.currentTimeMillis() / 1000,
                    feeSats = fee,
                    totalOutSats = totalOut,
                    receivedSats = receivedSats,
                    spentSats = spentSats,
                    netSats = receivedSats - spentSats,
                    direction = direction
                )
            }
            Result.success(txs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get address txs for $address", e)
            Result.failure(e)
        }
    }

    /**
     * Get spendable UTXOs for an address (confirmed only).
     */
    suspend fun getAddressUtxos(address: String): Result<List<Utxo>> {
        return try {
            val arr = Json.parseToJsonElement(apiGet("/address/$address/utxo")).jsonArray
            val utxos = arr.mapNotNull { el ->
                val obj = el.jsonObject
                val txid = obj["txid"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val vout = obj["vout"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
                val value = obj["value"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
                val status = obj["status"]?.jsonObject
                val confirmed = status?.get("confirmed")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                if (!confirmed) return@mapNotNull null
                Utxo(txid, vout, value)
            }
            Result.success(utxos)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get utxos for $address", e)
            Result.failure(e)
        }
    }

    data class AddressInfo(
        val confirmedBalanceSats: Long,
        val unconfirmedBalanceSats: Long
    ) {
        val totalSats: Long get() = confirmedBalanceSats + unconfirmedBalanceSats
    }

    data class AddressTx(
        val txid: String,
        val confirmed: Boolean,
        val blockTimeSec: Long,
        val feeSats: Long,
        val totalOutSats: Long,
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
        val confirmations: Long
    )
}
