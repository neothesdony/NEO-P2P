package com.neop2p.data.wallet

import android.util.Log
import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.data.p2p.IdentityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.core.Utils
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal wallet service: balance, history, and sending BTC from the user's
 * own BIP-44 address (m/44'/0'/0'/0/0). Mirrors the EscrowService pattern:
 * raw bitcoinj Transaction + ECKey signing + Mempool broadcast.
 */
@Singleton
class WalletService @Inject constructor(
    private val identityManager: IdentityManager,
    private val chainMonitor: ChainMonitor
) {
    companion object {
        private const val TAG = "WalletService"
        // P2PKH spend: 1 input (148 vbytes) + 2 outputs (34+34) + overhead (~10)
        private const val P2PKH_SPEND_APPROX_VSIZE = 226L
    }

    private val params: NetworkParameters
        get() = if (com.neop2p.BuildConfig.NETWORK == "mainnet") MainNetParams.get() else TestNet3Params.get()

    data class WalletState(
        val address: String,
        val confirmedSats: Long = 0,
        val unconfirmedSats: Long = 0,
        val txs: List<ChainMonitor.AddressTx> = emptyList()
    ) {
        val totalSats: Long get() = confirmedSats + unconfirmedSats
    }

    data class SendResult(
        val txid: String,
        val feeSats: Long
    )

    /** The user's own receive address (deterministic from the BIP-39 seed). */
    fun myAddress(): String = identityManager.getBitcoinAddress()

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    suspend fun loadState(): Result<WalletState> = withContext(Dispatchers.IO) {
        try {
            val address = myAddress()
            val info = chainMonitor.getAddressInfo(address).getOrNull()
            val txs = chainMonitor.getAddressTxs(address).getOrDefault(emptyList())
            Result.success(
                WalletState(
                    address = address,
                    confirmedSats = info?.confirmedBalanceSats ?: 0L,
                    unconfirmedSats = info?.unconfirmedBalanceSats ?: 0L,
                    txs = txs
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet state", e)
            Result.failure(e)
        }
    }

    /**
     * Send BTC from the user's own address to [toAddress].
     *
     * Selects confirmed UTXOs (greedy), builds a raw P2PKH tx with change
     * back to the sender, signs with the BIP-44 key, and broadcasts via
     * Mempool. Fee = fastest rate × estimated vsize.
     */
    suspend fun send(toAddress: String, amountSats: Long): Result<SendResult> =
        withContext(Dispatchers.IO) {
            try {
                val address = myAddress()
                val utxos = chainMonitor.getAddressUtxos(address).getOrNull()
                    ?: return@withContext Result.failure(Exception("Could not fetch UTXOs"))
                if (utxos.isEmpty()) {
                    return@withContext Result.failure(Exception("No confirmed balance to send"))
                }

                val feeRate = chainMonitor.estimateFees().fastest
                val feeSats = feeRate * P2PKH_SPEND_APPROX_VSIZE

                // Greedy UTXO selection.
                var selected = 0L
                val chosen = mutableListOf<ChainMonitor.Utxo>()
                for (u in utxos.sortedByDescending { it.valueSats }) {
                    if (selected >= amountSats + feeSats) break
                    chosen.add(u)
                    selected += u.valueSats
                }
                if (selected < amountSats + feeSats) {
                    return@withContext Result.failure(
                        Exception("Insufficient balance: have ${selected}sats, need ${amountSats + feeSats}sats")
                    )
                }

                val tx = Transaction(params)
                for (u in chosen) {
                    tx.addInput(Sha256Hash.wrap(u.txid), u.vout, ScriptBuilder.createEmpty())
                }
                tx.addOutput(Coin.valueOf(amountSats), LegacyAddress.fromBase58(params, toAddress))
                val change = selected - amountSats - feeSats
                if (change > 546) { // dust threshold
                    tx.addOutput(Coin.valueOf(change), LegacyAddress.fromBase58(params, address))
                }

                // Sign every input with the BIP-44 key (P2PKH: hashForSignature
                // against the output script). Mirrors EscrowService.signTransaction.
                val key = ECKey.fromPrivate(hexToBytes(identityManager.getBitcoinPrivateKeyHex()))
                val outputScript = ScriptBuilder.createOutputScript(LegacyAddress.fromBase58(params, address))
                for (i in tx.inputs.indices) {
                    val hash = tx.hashForSignature(i, outputScript, Transaction.SigHash.ALL, false)
                    val sig = key.sign(hash)
                    // DER sig + SIGHASH_ALL
                    val sigEncoded = sig.encodeToDER() + byteArrayOf(Transaction.SigHash.ALL.value.toByte())
                    val txSig = org.bitcoinj.crypto.TransactionSignature.decodeFromBitcoin(sigEncoded, false, false)
                    tx.getInput(i.toLong()).setScriptSig(ScriptBuilder.createInputScript(txSig, key))
                }

                val txHex = tx.bitcoinSerialize().joinToString("") { "%02x".format(it) }
                val txid = chainMonitor.broadcastTx(txHex).getOrElse {
                    return@withContext Result.failure(it)
                }
                Log.i(TAG, "Sent $amountSats sats to $toAddress (txid=$txid, fee=$feeSats)")
                Result.success(SendResult(txid, feeSats))
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                Result.failure(e)
            }
        }
}
