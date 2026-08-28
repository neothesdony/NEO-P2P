package com.neop2p.data.wallet

import android.util.Log
import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.domain.model.BitcoinAddressType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.SegwitAddress
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionWitness
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal wallet service: balance, history, and sending BTC from the user's
 * own BIP-44 addresses (m/44'/0'/0'/0/0).
 *
 * The user holds BOTH a legacy (P2PKH) and a SegWit (P2WPKH) address derived
 * from the same key. Balance/history aggregate both; sends select UTXOs from
 * either type and sign with the matching sighash (legacy `hashForSignature`
 * vs BIP-143 `calculateWitnessSignature`), so funds received on one type can
 * be spent regardless of which address the user shows.
 */
@Singleton
class WalletService @Inject constructor(
    private val identityManager: IdentityManager,
    private val chainMonitor: ChainMonitor
) {
    companion object {
        private const val TAG = "WalletService"
        // Per-input overhead for a P2PKH spend (~148 vbytes) and per-output
        // overhead (~34 vbytes), plus ~10 vbytes of fixed tx overhead.
        private const val P2PKH_INPUT_VSIZE = 148L
        private const val OUTPUT_VSIZE = 34L
        private const val FIXED_OVERHEAD_VSIZE = 10L
        private const val DUST_THRESHOLD_SATS = 546L
    }

    private val params: NetworkParameters
        get() = if (com.neop2p.BuildConfig.NETWORK == "mainnet") MainNetParams.get() else TestNet3Params.get()

    data class WalletState(
        val addresses: Map<BitcoinAddressType, String>,
        val confirmedSats: Long = 0,
        val unconfirmedSats: Long = 0,
        val txs: List<ChainMonitor.AddressTx> = emptyList()
    ) {
        val totalSats: Long get() = confirmedSats + unconfirmedSats
        /** Default receive address: SegWit (cheaper spends, modern default). */
        val address: String get() = addresses[BitcoinAddressType.SEGWIT].orEmpty()
        fun addressFor(type: BitcoinAddressType): String =
            addresses[type].orEmpty()
    }

    data class SendResult(
        val txid: String,
        val feeSats: Long
    )

    /** The user's receive address for [type] (deterministic from the seed). */
    fun myAddress(type: BitcoinAddressType): String = identityManager.getBitcoinAddress(type)

    /** Both receive addresses (legacy + SegWit), keyed by type. */
    fun myAddresses(): Map<BitcoinAddressType, String> = identityManager.getBitcoinAddresses()

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
            val addresses = myAddresses()
            // Propagate API failures instead of rendering a fake zero balance:
            // a dead mempool/blockstream connection must surface as an error,
            // never as "0.00000000 BTC".
            var confirmed = 0L
            var unconfirmed = 0L
            val allTxs = mutableListOf<ChainMonitor.AddressTx>()
            for ((_, address) in addresses) {
                val info = chainMonitor.getAddressInfo(address).getOrElse {
                    return@withContext Result.failure(
                        Exception("Could not fetch wallet balance: ${it.message}")
                    )
                }
                val txs = chainMonitor.getAddressTxs(address).getOrElse {
                    return@withContext Result.failure(
                        Exception("Could not fetch wallet history: ${it.message}")
                    )
                }
                confirmed += info.confirmedBalanceSats
                unconfirmed += info.unconfirmedBalanceSats
                allTxs.addAll(txs)
            }
            // History across both addresses, newest first, deduped by txid.
            val deduped = allTxs
                .distinctBy { it.txid }
                .sortedByDescending { it.blockTimeSec }
            Result.success(
                WalletState(
                    addresses = addresses,
                    confirmedSats = confirmed,
                    unconfirmedSats = unconfirmed,
                    txs = deduped
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet state", e)
            Result.failure(e)
        }
    }

    /**
     * Estimate the miner fee for a send of [amountSats] from [fromType] (or
     * mixed) WITHOUT broadcasting anything. Used for the send-confirm preview
     * so the user sees fee + total before the irreversible broadcast.
     *
     * The real fee is recomputed after UTXO selection inside [send]; this is
     * an honest preview estimate on a single SegWit input (the common case),
     * so a multi-input spend may cost slightly more than shown.
     */
    suspend fun estimateSendFee(
        amountSats: Long,
        fromType: BitcoinAddressType? = null
    ): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val feeRate = chainMonitor.estimateFees().fastest
                val inputVsize = when (fromType) {
                    BitcoinAddressType.LEGACY -> P2PKH_INPUT_VSIZE
                    else -> BitcoinAddressType.SEGWIT.inputVsize
                }
                // 1 input + send output + change output + overhead (mirrors
                // the single-input case inside send()).
                feeRate * (inputVsize + 2 * OUTPUT_VSIZE + FIXED_OVERHEAD_VSIZE)
            }
        }

    /**
     * Selects confirmed UTXOs across BOTH the legacy and SegWit addresses
     * (greedy), builds a raw tx with change back to the sender's SegWit
     * address, and signs each input with the BIP-44 key using the sighash
     * matching its script type (legacy sighash for P2PKH, BIP-143 witness
     * sighash for P2WPKH). The fee is computed AFTER UTXO selection so
     * multi-input sends pay for every input (per-type vbytes).
     *
     * @param fromType when non-null, spend ONLY UTXOs of that type (the
     *   wallet screen's "send from" selector); when null, spend across both.
     */
    suspend fun send(
        toAddress: String,
        amountSats: Long,
        fromType: BitcoinAddressType? = null
    ): Result<SendResult> =
        withContext(Dispatchers.IO) {
            try {
                val addresses = myAddresses()
                val destination = try {
                    Address.fromString(params, toAddress)
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        Exception("Invalid destination address: $toAddress")
                    )
                }
                // Collect UTXOs from both wallet addresses, tagged with their type.
                val taggedUtxos = mutableListOf<Pair<BitcoinAddressType, ChainMonitor.Utxo>>()
                for ((type, address) in addresses) {
                    if (fromType != null && type != fromType) continue
                    val utxos = chainMonitor.getAddressUtxos(address).getOrElse {
                        return@withContext Result.failure(Exception("Could not fetch UTXOs"))
                    }
                    utxos.forEach { taggedUtxos.add(type to it) }
                }
                if (taggedUtxos.isEmpty()) {
                    return@withContext Result.failure(
                        if (fromType != null)
                            Exception("No confirmed ${fromType.name.lowercase()} balance to send")
                        else
                            Exception("No confirmed balance to send")
                    )
                }

                // Greedy UTXO selection across both types. Fee is estimated on
                // 1 segwit input first, then recomputed for the actual input
                // mix once selection has settled (each input adds its own
                // per-type vbytes).
                val feeRate = chainMonitor.estimateFees().fastest
                var feeSats = feeRate * (BitcoinAddressType.SEGWIT.inputVsize + 2 * OUTPUT_VSIZE + FIXED_OVERHEAD_VSIZE)
                var selected = 0L
                val chosen = mutableListOf<Pair<BitcoinAddressType, ChainMonitor.Utxo>>()
                for (u in taggedUtxos.sortedByDescending { it.second.valueSats }) {
                    if (selected >= amountSats + feeSats) break
                    chosen.add(u)
                    selected += u.second.valueSats
                }
                // Now that we know the input mix, charge the real fee:
                // inputs × per-type vbytes + outputs × per-type vbytes + overhead.
                // Change goes back to the SEGWIT address (cheaper future spends,
                // keeps the wallet segwit-native instead of draining into legacy).
                val changeType = BitcoinAddressType.SEGWIT
                feeSats = feeRate * (chosen.sumOf { it.first.inputVsize } +
                    OUTPUT_VSIZE + changeType.outputVsize + FIXED_OVERHEAD_VSIZE)
                if (selected < amountSats + feeSats) {
                    return@withContext Result.failure(
                        Exception("Insufficient balance: have ${selected}sats, need ${amountSats + feeSats}sats")
                    )
                }

                val tx = Transaction(params)
                for ((_, u) in chosen) {
                    tx.addInput(Sha256Hash.wrap(u.txid), u.vout.toLong(), ScriptBuilder.createEmpty())
                }
                tx.addOutput(Coin.valueOf(amountSats), destination)
                val change = selected - amountSats - feeSats
                val changeAddress = addresses[BitcoinAddressType.SEGWIT]!!
                if (change > DUST_THRESHOLD_SATS) {
                    tx.addOutput(
                        Coin.valueOf(change),
                        SegwitAddress.fromBech32(params, changeAddress)
                    )
                }

                // Sign every input with the BIP-44 key. P2PKH inputs use the
                // legacy sighash against the P2PKH output script; P2WPKH inputs
                // use the BIP-143 witness sighash (value-committed) and put the
                // signature in the witness, not the scriptSig.
                val key = ECKey.fromPrivate(hexToBytes(identityManager.getBitcoinPrivateKeyHex()))
                for (i in tx.inputs.indices) {
                    val (type, _) = chosen[i]
                    if (type == BitcoinAddressType.LEGACY) {
                        val address = addresses[BitcoinAddressType.LEGACY]!!
                        val outputScript = ScriptBuilder.createOutputScript(LegacyAddress.fromBase58(params, address))
                        val hash = tx.hashForSignature(i, outputScript, Transaction.SigHash.ALL, false)
                        val sig = key.sign(hash)
                        val sigEncoded = sig.encodeToDER() + byteArrayOf(Transaction.SigHash.ALL.value.toByte())
                        val txSig = org.bitcoinj.crypto.TransactionSignature.decodeFromBitcoin(sigEncoded, false, false)
                        tx.getInput(i.toLong()).setScriptSig(ScriptBuilder.createInputScript(txSig, key))
                    } else {
                        val address = addresses[BitcoinAddressType.SEGWIT]!!
                        val segwitAddr = SegwitAddress.fromBech32(params, address)
                        // BIP-143: the scriptCode committed in the witness sighash
                        // for P2WPKH is the *P2PKH script*, NOT the OP_0 <hash160>
                        // output script. bitcoinj's own addSignedInput() passes
                        // ScriptBuilder.createP2PKHOutputScript(key) here; passing
                        // the output script hashes a different message than the
                        // node verifies → -mempool-script-verify-flag- on broadcast
                        // ("send to escrow" failure on real devices).
                        val scriptCode = ScriptBuilder.createP2PKHOutputScript(key)
                        val inputValue = Coin.valueOf(chosen[i].second.valueSats)
                        val txSig = tx.calculateWitnessSignature(i, key, scriptCode, inputValue, Transaction.SigHash.ALL, false)
                        val witness = TransactionWitness.redeemP2WPKH(txSig, key)
                        tx.getInput(i.toLong()).setWitness(witness)
                    }
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
