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
 * A Mempool/Esplora-shaped provider, parameterized by [base]. Most mirrors
 * support every capability; a stripped instance (e.g. btcscan.org, which has
 * no `/v1/fees/recommended`) is built with a narrower [capabilities] set.
 *
 * All parsing reuses the pure `ChainMonitor` companion parsers where they
 * exist, so Esplora behavior is byte-for-byte the pre-refactor behavior.
 */
class EsploraProvider(
    override val id: String,
    private val base: String,
    private val httpClient: HttpClient,
    override val capabilities: Set<Capability> = ALL,
) : ExplorerProvider {

    companion object {
        val ALL: Set<Capability> = Capability.entries.toSet()
        val ALL_BUT_FEES: Set<Capability> = ALL - Capability.FEES

        /**
         * A mirror that serves chain tip + fee estimates but has no address
         * index (2026-09-17: mempool.emzy.de's testnet4 returns 404 for
         * `/address/...` while `/blocks/tip/height` works). Declaring only what
         * it can serve keeps the wallet's ~60-address scan from paying a wasted
         * failing round-trip per open.
         */
        val TIP_AND_FEES: Set<Capability> = setOf(Capability.TIP, Capability.FEES)
    }

    private suspend fun getJson(path: String): String {
        val body = httpClient.get("$base$path").bodyAsText().trim()
        if (body.isEmpty() || (!body.startsWith("{") && !body.startsWith("["))) {
            throw IllegalStateException("Explorer returned non-JSON response: ${body.take(80)}")
        }
        return body
    }

    private suspend fun getText(path: String): String {
        val body = httpClient.get("$base$path").bodyAsText().trim()
        if (body.toLongOrNull() == null) {
            throw IllegalStateException("Explorer returned non-numeric response: ${body.take(80)}")
        }
        return body
    }

    private suspend fun post(path: String, body: String): String =
        httpClient.post("$base$path") {
            setBody(body)
            contentType(ContentType.Text.Plain)
        }.bodyAsText().trim()

    override suspend fun txInfo(txid: String, tipHeight: Long?): ChainMonitor.TxInfo? = try {
        ChainMonitor.parseTxInfo(getJson("/tx/$txid"), tipHeight)
    } catch (e: Exception) {
        null
    }

    override suspend fun txOutputs(txid: String): List<ChainMonitor.TxOutput>? = try {
        ChainMonitor.parseTxOutputs(getJson("/tx/$txid"))
    } catch (e: Exception) {
        null
    }

    override suspend fun addressInfo(address: String): ChainMonitor.AddressInfo? = try {
        val json = Json.parseToJsonElement(getJson("/address/$address")).jsonObject
        val stats = json["chain_stats"]?.jsonObject
        val mempool = json["mempool_stats"]?.jsonObject
        val confirmed = stats?.get("funded_txo_sum")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val spent = stats?.get("spent_txo_sum")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val unconfirmed = mempool?.get("funded_txo_sum")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val unconfirmedSpent = mempool?.get("spent_txo_sum")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val chainTxCount = stats?.get("tx_count")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val mempoolTxCount = mempool?.get("tx_count")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        ChainMonitor.AddressInfo(
            confirmedBalanceSats = confirmed - spent,
            unconfirmedBalanceSats = unconfirmed - unconfirmedSpent,
            txCount = chainTxCount + mempoolTxCount
        )
    } catch (e: Exception) {
        null
    }

    override suspend fun addressTxs(address: String, limit: Int): List<ChainMonitor.AddressTx>? = try {
        val arr = Json.parseToJsonElement(getJson("/address/$address/txs")).jsonArray
        arr.take(limit).mapNotNull { el ->
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
            // (mempool.space includes it). When missing, every spend would be
            // misclassified as RECEIVE — resolve the real inputs by fetching
            // the full tx and reading prevout from there.
            val resolvedSpentSats = if (spentSats == 0L && vin.isNotEmpty()) {
                runCatching {
                    val full = Json.parseToJsonElement(getJson("/tx/$txid")).jsonObject
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
                resolvedSpentSats == 0L -> ChainMonitor.TxDirection.RECEIVE
                receivedSats < resolvedSpentSats -> ChainMonitor.TxDirection.SEND
                else -> ChainMonitor.TxDirection.SELF
            }
            ChainMonitor.AddressTx(
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
    } catch (e: Exception) {
        null
    }

    override suspend fun addressUtxos(address: String): List<ChainMonitor.Utxo>? = try {
        val arr = Json.parseToJsonElement(getJson("/address/$address/utxo")).jsonArray
        arr.mapNotNull { el ->
            val obj = el.jsonObject
            val txid = obj["txid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val vout = obj["vout"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            val value = obj["value"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val confirmed = obj["status"]?.jsonObject
                ?.get("confirmed")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            if (!confirmed) return@mapNotNull null
            ChainMonitor.Utxo(txid, vout, value)
        }
    } catch (e: Exception) {
        null
    }

    override suspend fun feeEstimate(): ChainMonitor.FeeEstimate? = try {
        val json = Json.parseToJsonElement(getJson("/v1/fees/recommended")).jsonObject
        ChainMonitor.FeeEstimate(
            fastest = json["fastestFee"]?.jsonPrimitive?.content?.toLongOrNull() ?: 50L,
            halfHour = json["halfHourFee"]?.jsonPrimitive?.content?.toLongOrNull() ?: 30L,
            hour = json["hourFee"]?.jsonPrimitive?.content?.toLongOrNull() ?: 20L
        )
    } catch (e: Exception) {
        null
    }

    override suspend fun tipHeight(): Long? = try {
        getText("/blocks/tip/height").toLongOrNull()
    } catch (e: Exception) {
        null
    }

    override suspend fun broadcast(txHex: String, expectedTxid: String?): String? = try {
        val response = post("/tx", txHex)
        if (ChainMonitor.broadcastAccepted(response, expectedTxid)) response else null
    } catch (e: Exception) {
        null
    }
}
