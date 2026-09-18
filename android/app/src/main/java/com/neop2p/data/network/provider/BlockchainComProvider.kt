package com.neop2p.data.network.provider

import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.data.network.Capability
import com.neop2p.data.network.ExplorerProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * blockchain.com's legacy data API adapter (verified keyless 2026-09-17).
 *
 * `rawtx` and `rawaddr` return a different JSON shape than Esplora; `out[]`
 * does carry `addr`, so no script->address decoding is needed. Amounts are
 * satoshis. `block_height` > 0 means confirmed.
 *
 * Deliberately does NOT advertise the address capabilities: blockchain.com
 * folds unconfirmed activity into `final_balance` differently from Esplora's
 * `chain_stats`/`mempool_stats`, and an address-balance mismatch could make the
 * escrow sweep wrongly conclude "no on-chain deposit". It serves the tx/UTXO
 * verification and broadcast paths only.
 */
class BlockchainComProvider(
    private val httpClient: HttpClient,
) : ExplorerProvider {

    override val id: String = "blockchain.com"

    override val capabilities: Set<Capability> = setOf(
        Capability.TX_INFO,
        Capability.TX_OUTPUTS,
        Capability.TIP,
        Capability.BROADCAST,
    )

    companion object {
        const val BASE: String = "https://blockchain.info"

        /** Pure parser for `/rawtx/{txid}?format=json`. `block_height` > 0 = confirmed. */
        fun parseTxInfo(json: String, tipHeight: Long?): ChainMonitor.TxInfo? {
            val obj = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                return null
            }
            val txid = obj["hash"]?.jsonPrimitive?.content ?: return null
            val blockHeight = obj["block_height"]?.jsonPrimitive?.content?.toLongOrNull()
            val confirmed = blockHeight != null && blockHeight > 0L
            val confirmations = when {
                !confirmed -> 0L
                tipHeight != null && blockHeight != null && tipHeight >= blockHeight ->
                    tipHeight - blockHeight + 1
                else -> 1L
            }
            val time = obj["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            return ChainMonitor.TxInfo(txid, confirmed, confirmations, time)
        }

        /** Pure parser for `/rawtx/{txid}?format=json` outputs. */
        fun parseTxOutputs(json: String): List<ChainMonitor.TxOutput>? {
            val obj = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                return null
            }
            val out = obj["out"]?.jsonArray ?: return emptyList()
            return out.mapIndexed { i, el ->
                val o = el.jsonObject
                ChainMonitor.TxOutput(
                    scriptPubkeyAddress = o["addr"]?.jsonPrimitive?.content,
                    valueSats = o["value"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    index = o["n"]?.jsonPrimitive?.content?.toIntOrNull() ?: i
                )
            }
        }

        /** Pure parser for `/latestblock`. */
        fun parseTipHeight(json: String): Long? = try {
            Json.parseToJsonElement(json).jsonObject["height"]?.jsonPrimitive?.content?.toLongOrNull()
        } catch (e: Exception) {
            null
        }

        /**
         * Pure success detector for `POST /pushtx`. blockchain.com answers with
         * prose ("Transaction Submitted"), so an exact txid is only returned by
         * the caller when the response signals success.
         */
        fun broadcastSucceeded(body: String): Boolean =
            body.trim().contains("submitted", ignoreCase = true)
    }

    private suspend fun getJson(path: String): String {
        val body = httpClient.get("$BASE$path").bodyAsText().trim()
        if (body.isEmpty() || (!body.startsWith("{") && !body.startsWith("["))) {
            throw IllegalStateException("blockchain.com returned non-JSON response: ${body.take(80)}")
        }
        return body
    }

    override suspend fun txInfo(txid: String, tipHeight: Long?): ChainMonitor.TxInfo? = try {
        parseTxInfo(getJson("/rawtx/$txid?format=json"), tipHeight)
    } catch (e: Exception) {
        null
    }

    override suspend fun txOutputs(txid: String): List<ChainMonitor.TxOutput>? = try {
        parseTxOutputs(getJson("/rawtx/$txid?format=json"))
    } catch (e: Exception) {
        null
    }

    override suspend fun tipHeight(): Long? = try {
        parseTipHeight(getJson("/latestblock"))
    } catch (e: Exception) {
        null
    }

    override suspend fun broadcast(txHex: String, expectedTxid: String?): String? = try {
        val response = httpClient.post("$BASE/pushtx") {
            setBody("tx=$txHex")
            contentType(ContentType.Application.FormUrlEncoded)
        }.bodyAsText().trim()
        // blockchain.com never echoes the txid; it can only be trusted when the
        // caller already knows the txid it built.
        if (expectedTxid != null && broadcastSucceeded(response)) expectedTxid else null
    } catch (e: Exception) {
        null
    }
}
