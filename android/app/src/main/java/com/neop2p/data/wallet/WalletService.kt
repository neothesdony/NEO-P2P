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
import com.neop2p.data.wallet.WalletService.Companion.FIXED_OVERHEAD_VSIZE
import com.neop2p.data.wallet.WalletService.Companion.MIN_WALLET_FEE_SATS
import com.neop2p.data.wallet.WalletService.Companion.P2PKH_OUTPUT_VSIZE
import com.neop2p.data.wallet.WalletService.Companion.P2WPKH_OUTPUT_VSIZE
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
        // Per-input overhead: P2PKH ≈ 148 vbytes, P2WPKH ≈ 68 vbytes.
        internal const val P2PKH_INPUT_VSIZE = 148L
        // Per-output overhead: P2PKH ≈ 34 vbytes, P2WPKH ≈ 31 vbytes.
        internal const val P2PKH_OUTPUT_VSIZE = 34L
        internal const val P2WPKH_OUTPUT_VSIZE = 31L
        internal const val FIXED_OVERHEAD_VSIZE = 10L
        private const val DUST_THRESHOLD_SATS = 546L
        /** Minimum wallet send fee to stay above minrelaytxfee (1 sat/vB). */
        internal const val MIN_WALLET_FEE_SATS = 250L
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
     * Runs the SAME greedy selection as [send] (fetching the real UTXOs), so
     * a multi-input spend shows the true fee — not a 1-input guess. Fails
     * when there are no confirmed UTXOs of the requested type (the dialog
     * then shows no fee line and the send itself will fail honestly).
     */
    suspend fun estimateSendFee(
        amountSats: Long,
        fromType: BitcoinAddressType? = null
    ): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val addresses = myAddresses()
                val taggedUtxos = mutableListOf<Pair<BitcoinAddressType, ChainMonitor.Utxo>>()
                for ((type, address) in addresses) {
                    if (fromType != null && type != fromType) continue
                    chainMonitor.getAddressUtxos(address).getOrThrow()
                        .forEach { taggedUtxos.add(type to it) }
                }
                if (taggedUtxos.isEmpty()) {
                    throw IllegalStateException("No confirmed UTXOs to estimate fee")
                }
                val feeRate = chainMonitor.estimateFees().fastest
                selectSpend(taggedUtxos, amountSats, feeRate).feeSats
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
     * @param maxFeeSats the fee shown to the user in the confirm dialog; the
     *   send fails when the freshly computed fee exceeds it (null = no
     *   preview was shown; the absolute 5% cap still applies).
     */
    suspend fun send(
        toAddress: String,
        amountSats: Long,
        fromType: BitcoinAddressType? = null,
        maxFeeSats: Long? = null
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

                val feeRate = chainMonitor.estimateFees().fastest
                val spend = selectSpend(taggedUtxos, amountSats, feeRate)
                // Audit P1-2 (2026-09-12): [maxFeeSats] is the fee the user
                // confirmed in the dialog. The rate is re-fetched above, so the
                // fee can differ from the preview; refuse to broadcast a fee
                // the user never agreed to (and never breach the 5% cap).
                WalletFeePolicy.rejectReason(spend.feeSats, amountSats, maxFeeSats)?.let { reason ->
                    return@withContext Result.failure(IllegalStateException(reason))
                }
                if (spend.selectedSats < amountSats + spend.feeSats) {
                    return@withContext Result.failure(
                        Exception("Insufficient balance: have ${spend.selectedSats}sats, need ${amountSats + spend.feeSats}sats")
                    )
                }
                val chosen = spend.chosen
                val feeSats = spend.feeSats
                val selected = spend.selectedSats

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
                // Audit P3-1 (2026-09-12): when the change output is dropped as
                // dust the remainder stays in the tx and the miner collects it,
                // so report the fee actually paid — not the computed one.
                val effectiveFee = WalletFeePolicy.effectiveFeeSats(
                    computedFeeSats = feeSats,
                    changeSats = change,
                    dustThresholdSats = DUST_THRESHOLD_SATS
                )

                // Sign every input with the BIP-44 key. P2PKH inputs use the
                // legacy sighash against the P2PKH output script; P2WPKH inputs
                // use the BIP-143 witness sighash (value-committed) and put the
                // signature in the witness, not the scriptSig.
                val privBytes = identityManager.getBitcoinPrivateKeyBytes()
                val key = try {
                    ECKey.fromPrivate(privBytes)
                } finally {
                    // ECKey.fromPrivate copies the scalar into its own
                    // BigInteger; the raw array is ours to wipe (audit P3-4).
                    privBytes.fill(0)
                }
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

                // Audit P2-1 (2026-09-12): compute our own txid and require the
                // explorer to echo it back, so a wrong/forged txid can never be
                // shown to the user or stored on an escrow.
                val localTxid = tx.getHashAsString()
                val txHex = tx.bitcoinSerialize().joinToString("") { "%02x".format(it) }
                val txid = chainMonitor.broadcastTx(txHex, localTxid).getOrElse {
                    return@withContext Result.failure(it)
                }
                Log.i(TAG, "Sent $amountSats sats (txid=$txid, fee=$effectiveFee sats)")
                Result.success(SendResult(txid, effectiveFee))
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                Result.failure(e)
            }
        }
}

/** Result of greedy UTXO selection: the chosen inputs, the real fee, and the sum selected. */
data class SelectedSpend(
    val chosen: List<Pair<BitcoinAddressType, ChainMonitor.Utxo>>,
    val feeSats: Long,
    val selectedSats: Long
)

/**
 * Greedy UTXO selection + fee math shared by [WalletService.send] and
 * [WalletService.estimateSendFee]. Pure: no network, no Android.
 *
 * Fee is estimated on 1 SegWit input first, then recomputed for the ACTUAL
 * input mix once selection settles (each input adds its own per-type
 * vbytes). Change is assumed to go back to the SEGWIT address; the send
 * output uses the P2PKH upper bound (destination may be legacy).
 */
internal fun selectSpend(
    taggedUtxos: List<Pair<BitcoinAddressType, ChainMonitor.Utxo>>,
    amountSats: Long,
    feeRate: Long
): SelectedSpend {
    var feeSats = maxOf(
        feeRate * (BitcoinAddressType.SEGWIT.inputVsize + P2PKH_OUTPUT_VSIZE + P2WPKH_OUTPUT_VSIZE + FIXED_OVERHEAD_VSIZE),
        MIN_WALLET_FEE_SATS
    )
    var selected = 0L
    val chosen = mutableListOf<Pair<BitcoinAddressType, ChainMonitor.Utxo>>()
    for (u in taggedUtxos.sortedByDescending { it.second.valueSats }) {
        if (selected >= amountSats + feeSats) break
        chosen.add(u)
        selected += u.second.valueSats
    }
    val changeType = BitcoinAddressType.SEGWIT
    val realFee = maxOf(
        feeRate * (chosen.sumOf { it.first.inputVsize } +
            P2PKH_OUTPUT_VSIZE + changeType.outputVsize + FIXED_OVERHEAD_VSIZE),
        MIN_WALLET_FEE_SATS
    )
    return SelectedSpend(chosen, realFee, selected)
}
