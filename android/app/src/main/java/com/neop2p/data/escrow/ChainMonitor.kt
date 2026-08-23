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
        private const val TAG = "ChainMonitor"

        /** Use the testnet Mempool endpoint when the app runs on testnet. */
        private val MEMPOOL_BASE: String =
            if (BuildConfig.NETWORK == "mainnet") MEMPOOL_BASE_MAINNET else MEMPOOL_BASE_TESTNET
    }

    /**
     * Get recommended fee rates in sat/vB.
     * Returns (fastest, halfHour, hour) or defaults if API fails.
     */
    suspend fun estimateFees(): FeeEstimate {
        return try {
            val response = httpClient.get("$MEMPOOL_BASE/v1/fees/recommended")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
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
            val response = httpClient.post("$MEMPOOL_BASE/tx") {
                setBody(txHex)
                contentType(ContentType.Text.Plain)
            }
            val txid = response.bodyAsText().trim()
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
            val response = httpClient.get("$MEMPOOL_BASE/tx/$txid")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val confirmations = json["confirmations"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val status = json["status"]?.jsonObject
            val confirmed = status?.get("confirmed")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            Result.success(TxInfo(txid, confirmed, confirmations))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get tx info: ${e.message}")
            Result.failure(e)
        }
    }

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
