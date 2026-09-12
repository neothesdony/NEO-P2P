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
        // ─── Explorer API bases, tried in order ──────────────────────────
        // Primary: mempool.space (official). It times out from some networks
        // (observed 2026-08-24/25), so each call rotates through the mirrors
        // below before giving up. Every mirror must expose the same JSON API
        // (Mempool / Esplora shapes). All were probed 2026-08-25:
        //   - mempool.emzy.de   serves /testnet4/api (verified, reachable)
        //   - blockstream.info  serves mainnet+testnet3 only; /testnet4/ is a
        //     SPA HTML page (HTTP 200, NOT JSON) — bodyOrThrow rejects it, so
        //     it is harmless in the chain and useful as mainnet fallback.
        private val EXPLORER_BASES_MAINNET: List<String> = listOf(
            "https://mempool.space/api",
            "https://mempool.emzy.de/api",
            "https://blockstream.info/api",
        )
        // Testnet4 (not Testnet3): faucet funds and escrow tests live on
        // Testnet4 since 2026-08. Addresses are format-compatible (m/n
        // prefixes), only the explorer network differs.
        private val EXPLORER_BASES_TESTNET: List<String> = listOf(
            "https://mempool.space/testnet4/api",
            "https://mempool.emzy.de/testnet4/api",
        )
        private const val TAG = "ChainMonitor"

        /** Use the testnet explorer list when the app runs on testnet. */
        private val EXPLORER_BASES: List<String> =
            if (BuildConfig.NETWORK == "mainnet") EXPLORER_BASES_MAINNET else EXPLORER_BASES_TESTNET

        /**
         * Pure parser for Mempool/Esplora `/tx/{txid}` confirmation info.
         *
         * The API does NOT return a `confirmations` field — only
         * `status.confirmed` + `status.block_height` (verified live against
         * mempool.space and mempool.emzy.de, 2026-08-28). Confirmations are
         * derived from the tip height: `tip - block_height + 1`. When the tip
         * is unknown but the tx IS confirmed, fall back to 1 (satisfies the
         * default required_confirmations=1 gate; deeper requirements fail
         * closed because the depth cannot be proven).
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
         * Pure parser for Mempool/Esplora `/tx/{txid}` vout JSON. Exposed as a
         * companion function so funding-output binding is unit-testable without
         * a network. Missing fields degrade to null/0, never throw.
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
         * Audit P2-1 (2026-09-12): a broadcast response is an acceptance only
         * when it is a 64-hex txid — and, when we know the tx we built, only
         * when it IS that txid. Mempool/Esplora return the txid as PLAIN TEXT
         * from POST /api/tx.
         */
        fun broadcastAccepted(body: String, expectedTxid: String?): Boolean {
            val trimmed = body.trim()
            if (!trimmed.matches(Regex("[0-9a-fA-F]{64}"))) return false
            return expectedTxid == null || trimmed.equals(expectedTxid, ignoreCase = true)
        }

        /**
         * Audit P2-1: reconciliation verdict when every explorer base failed to
         * answer. A tx the chain already knows under OUR txid was broadcast —
         * reporting "send failed" there invites a double spend.
         */
        fun reconciledAfterFailure(info: TxInfo?, expectedTxid: String): Boolean =
            info != null && info.txid.equals(expectedTxid, ignoreCase = true)
    }

    /**
     * GET from the first reachable explorer base. Tries each base in
     * [EXPLORER_BASES] order; a non-JSON 200 (e.g. an SPA HTML page served for
     * a missing path) is treated as a failure just like a timeout, so callers
     * never receive HTML disguised as a successful API response.
     */
    private suspend fun apiGet(path: String): String {
        var lastError: Exception? = null
        for (base in EXPLORER_BASES) {
            try {
                return bodyOrThrow(httpClient.get("$base$path").bodyAsText())
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "GET $base$path failed (${e.message}), trying next explorer")
            }
        }
        throw lastError ?: IllegalStateException("No explorer base configured")
    }

    /**
     * GET a PLAIN-TEXT endpoint (e.g. `/blocks/tip/height`, which Mempool
     * serves as a bare number, not JSON) from the first reachable explorer
     * base. Non-numeric bodies are rejected so callers never parse an HTML
     * error page as a height.
     */
    private suspend fun apiGetText(path: String): String {
        var lastError: Exception? = null
        for (base in EXPLORER_BASES) {
            try {
                val body = httpClient.get("$base$path").bodyAsText().trim()
                if (body.toLongOrNull() == null) {
                    throw IllegalStateException(
                        "Explorer returned non-numeric response: ${body.take(80)}"
                    )
                }
                return body
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "GET $base$path failed (${e.message}), trying next explorer")
            }
        }
        throw lastError ?: IllegalStateException("No explorer base configured")
    }

    /**
     * POST a raw tx to the first explorer base that accepts it, rotating
     * through [EXPLORER_BASES] on failure.
     *
     * Broadcast is special: Mempool/Esplora's `POST /api/tx` returns the
     * resulting txid as PLAIN TEXT (not JSON), so a plain-text 64-hex txid is
     * a SUCCESS. Other responses (HTML error pages, empty bodies) are rejected.
     */
    private suspend fun apiPost(path: String, body: String, expectedTxid: String? = null): String {
        var lastError: Exception? = null
        for (base in EXPLORER_BASES) {
            try {
                val response = httpClient.post("$base$path") {
                    setBody(body)
                    contentType(ContentType.Text.Plain)
                }.bodyAsText().trim()
                // Audit P2-1: only the txid we built counts as an acceptance —
                // a different 64-hex body must not be reported as our txid.
                if (broadcastAccepted(response, expectedTxid)) {
                    return response
                }
                throw IllegalStateException(
                    "Explorer returned unexpected broadcast response: ${response.take(500)}"
                )
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "POST $base$path failed (${e.message}), trying next explorer")
            }
        }
        throw lastError ?: IllegalStateException("No explorer base configured")
    }

    /**
     * Rejects non-JSON bodies (e.g. an SPA HTML page served with HTTP 200 for a
     * missing path) so callers never parse HTML as JSON.
     */
    private fun bodyOrThrow(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            throw IllegalStateException("Explorer returned non-JSON response: ${trimmed.take(80)}")
        }
        return body
    }

    /**
     * Get recommended fee rates in sat/vB.
     * Returns (fastest, halfHour, hour) or defaults if API fails.
     */
    suspend fun estimateFees(): FeeEstimate {
        return try {
            val json = Json.parseToJsonElement(apiGet("/v1/fees/recommended")).jsonObject
            // Audit P1-2 (2026-09-12): the rate is a third-party input that
            // feeds money math. Clamp it here — the single choke point every
            // caller shares (wallet sends, escrow network fees, refund fees) —
            // so no downstream call site can be handed an absurd rate.
            FeeEstimate(
                fastest = com.neop2p.data.wallet.WalletFeePolicy.clampRate(
                    json["fastestFee"]?.jsonPrimitive?.content?.toLongOrNull() ?: 50L
                ),
                halfHour = com.neop2p.data.wallet.WalletFeePolicy.clampRate(
                    json["halfHourFee"]?.jsonPrimitive?.content?.toLongOrNull() ?: 30L
                ),
                hour = com.neop2p.data.wallet.WalletFeePolicy.clampRate(
                    json["hourFee"]?.jsonPrimitive?.content?.toLongOrNull() ?: 20L
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Fee estimation failed, using defaults: ${e.message}")
            FeeEstimate(50L, 30L, 20L)
        }
    }

    /**
     * Broadcast a raw transaction hex to the Bitcoin network.
     *
     * @param expectedTxid the txid of the tx we built (`Transaction.getHashAsString()`).
     *   When non-null the response must equal it, and a total broadcast failure
     *   is reconciled against the chain before it is reported as a failure —
     *   a tx that another explorer already accepted must never surface as
     *   "send failed" (audit P2-1).
     */
    suspend fun broadcastTx(txHex: String, expectedTxid: String? = null): Result<String> {
        return try {
            val txid = apiPost("/tx", txHex, expectedTxid).trim()
            Log.i(TAG, "Transaction broadcast: $txid")
            Result.success(txid)
        } catch (e: Exception) {
            if (expectedTxid != null) {
                val info = getTxInfo(expectedTxid).getOrNull()
                if (reconciledAfterFailure(info, expectedTxid)) {
                    Log.i(TAG, "Broadcast reported failure but $expectedTxid is known to the chain — treating as sent")
                    return Result.success(expectedTxid)
                }
            }
            Log.e(TAG, "Broadcast failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Get transaction details (confirmations, outputs).
     *
     * Mempool/Esplora do NOT return a `confirmations` field on `/tx/{txid}`
     * (only `status.confirmed` + `status.block_height`), so the depth is
     * derived from the current tip height. The tip fetch is best-effort:
     * when it fails, a confirmed tx reports 1 confirmation (satisfies the
     * default required_confirmations=1 gate).
     */
    suspend fun getTxInfo(txid: String): Result<TxInfo> {
        return try {
            val json = apiGet("/tx/$txid")
            val tipHeight = runCatching {
                apiGetText("/blocks/tip/height").toLongOrNull()
            }.getOrNull()
            Result.success(parseTxInfo(json, tipHeight))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get tx info: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Get transaction outputs (vout array) for a txid. Used to verify that a
     * funding transaction actually pays the escrow address the expected
     * deposit, and to derive the real funding output index.
     */
    suspend fun getTxOutputs(txid: String): Result<List<TxOutput>> {
        return try {
            Result.success(parseTxOutputs(apiGet("/tx/$txid")))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get tx outputs: ${e.message}")
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
                // Blockstream's Esplora omits `prevout` in /address/:addr/txs
                // (mempool.space includes it). When missing, every spend would
                // be misclassified as RECEIVE — resolve the real inputs by
                // fetching the full tx and reading prevout from there.
                val resolvedSpentSats = if (spentSats == 0L && vin.isNotEmpty()) {
                    runCatching {
                        val full = Json.parseToJsonElement(apiGet("/tx/$txid")).jsonObject
                        full["vin"]?.jsonArray.orEmpty().sumOf { vi ->
                            val prevout = vi.jsonObject["prevout"]?.jsonObject
                            if (prevout?.get("scriptpubkey_address")?.jsonPrimitive?.content == address)
                                prevout["value"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L else 0L
                        }
                    }.getOrDefault(spentSats)
                } else {
                    spentSats
                }
                val direction = when {
                    resolvedSpentSats == 0L -> TxDirection.RECEIVE
                    receivedSats < resolvedSpentSats -> TxDirection.SEND
                    else -> TxDirection.SELF
                }
                AddressTx(
                    txid = txid,
                    confirmed = confirmed,
                    blockTimeSec = blockTime ?: System.currentTimeMillis() / 1000,
                    feeSats = fee,
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

    data class TxOutput(
        val scriptPubkeyAddress: String?,
        val valueSats: Long,
        val index: Int
    )

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
