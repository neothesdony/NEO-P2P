package com.neop2p.data.wallet

import android.util.Log
import com.neop2p.NeoP2PConfig
import com.neop2p.RuntimeIntegrity
import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.security.RuntimeIntegrityProbe
import com.neop2p.domain.model.BitcoinAddressType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.bitcoinj.base.Address
import org.bitcoinj.base.Coin
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.base.SegwitAddress
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
    private val chainMonitor: ChainMonitor,
    private val addressStateStore: WalletAddressStateStore,
    private val snapshotStore: WalletSnapshotStore,
    private val integrityProbe: RuntimeIntegrityProbe
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

        /** Per-address result cache life — a refresh within this window is free. */
        internal const val SCAN_CACHE_TTL_MS = 30_000L

        /**
         * Address fetches in flight at once. The scan window is ~60 addresses
         * and explorer RTT is often 250–600 ms, so strict serial scans took
         * ~35 s (2026-09-17). Kept deliberately small: public Esplora mirrors
         * rate-limit a fan-out.
         */
        internal const val SCAN_CONCURRENCY = 4

        /** Pacing between concurrency batches (applied per batch, not per call). */
        internal const val SCAN_BATCH_DELAY_MS = 40L

        /**
         * P2.3 anti-fee-sniping: the locktime is the EXACT chain tip, or 0 when
         * the tip is unknown (never a stale height). A tip locktime makes the
         * funding tx non-final for the current block, so a seller's escrow
         * deposit lands one block later — `required_confirmations` (default 1)
         * and the 30-min funding window absorb that.
         */
        internal fun lockTimeFor(tipHeight: Long?): Long =
            if (tipHeight != null && tipHeight > 0L) tipHeight else 0L
    }

    private data class AddressScan(
        val confirmedSats: Long,
        val unconfirmedSats: Long,
        val txs: List<ChainMonitor.AddressTx>
    ) {
        fun isActive(): Boolean = confirmedSats != 0L || unconfirmedSats != 0L || txs.isNotEmpty()
    }

    private data class ScanCacheEntry(val scan: AddressScan, val atMs: Long)

    private val scanCache = ConcurrentHashMap<String, ScanCacheEntry>()

    private val params: NetworkParameters
        get() = if (NeoP2PConfig.network == "mainnet") MainNetParams.get() else TestNet3Params.get()

    data class WalletState(
        val addresses: Map<BitcoinAddressType, String>,
        val confirmedSats: Long = 0,
        val unconfirmedSats: Long = 0,
        val txs: List<ChainMonitor.AddressTx> = emptyList(),
        /**
         * True when at least one address could not be refreshed and a cached
         * value was used instead (P0.4 partial-failure tolerance). A non-blocking
         * warning, not an error — the balance is real, just not freshly fetched.
         */
        val stale: Boolean = false
    ) {
        val totalSats: Long get() = confirmedSats + unconfirmedSats
    }

    data class SendResult(
        val txid: String,
        val feeSats: Long
    )

    /** A reserved receive index with both address encodings (P0.7). */
    data class ReceiveAddresses(
        val index: Int,
        val legacy: String,
        val segwit: String
    ) {
        fun forType(type: BitcoinAddressType): String =
            if (type == BitcoinAddressType.LEGACY) legacy else segwit

        fun asMap(): Map<BitcoinAddressType, String> = mapOf(
            BitcoinAddressType.LEGACY to legacy,
            BitcoinAddressType.SEGWIT to segwit
        )
    }

    /** The user's receive address for [type] (deterministic from the seed). */
    fun myAddress(type: BitcoinAddressType): String = identityManager.getBitcoinAddress(type)

    /** Both receive addresses (legacy + SegWit), keyed by type. */
    fun myAddresses(): Map<BitcoinAddressType, String> = identityManager.getBitcoinAddresses()

    /** Current HD pointers (P0.3 store), scoped to this identity. */
    internal fun currentPointers(): HdPointers =
        addressStateStore.load(currentIdentityKey())

    private fun currentIdentityKey(): String =
        runCatching { identityManager.myPeerId() }.getOrNull().orEmpty()

    private fun savePointers(pointers: HdPointers): Boolean =
        addressStateStore.save(pointers, currentIdentityKey())

    /**
     * P0.8: move `nextExternal` just past the highest used external index. The
     * `+gap` lookahead stays. Never moves `nextChange` (P0.6 owns it write-ahead)
     * and never moves a pointer backwards.
     */
    private fun advanceExternalPointer(scanned: List<ScannedAddress>, activeAddresses: Set<String>) {
        val pointers = currentPointers()
        val activeIndices = scanned
            .filter { !it.internal && it.address in activeAddresses }
            .map { it.index }
            .toSet()
        val used = usedIndices(activeIndices, scanSet(pointers.nextExternal))
        val advanced = advance(pointers, usedExternal = used, usedChange = emptySet())
        if (advanced != pointers) savePointers(advanced)
    }

    /**
     * (P0.7) Reserve a fresh receive index and return both encodings. The ONLY
     * choke point for the wallet receive flow — no screen derives its own
     * address. Escrow-bound addresses are pinned at index-0 external and never
     * go through here.
     */
    fun reserveReceiveAddress(): ReceiveAddresses {
        val pointers = currentPointers()
        val index = pickReceiveIndex(pointers)
        savePointers(reserve(pointers, index))
        return ReceiveAddresses(
            index = index,
            legacy = identityManager.getBitcoinAddress(BitcoinAddressType.LEGACY, index, internal = false),
            segwit = identityManager.getBitcoinAddress(BitcoinAddressType.SEGWIT, index, internal = false)
        )
    }

    /** (P0.7) Release a reservation — the receive flow was torn down. */
    fun releaseReceiveIndex(index: Int) {
        savePointers(release(currentPointers(), index))
    }

    /**
     * The SINGLE address enumeration (P0.4): balance, UTXO collection, and the
     * receive watcher all use this. Never enumerate addresses anywhere else.
     */
    internal fun scanSetAddresses(): List<ScannedAddress> =
        scanAddresses(currentPointers()) { type, index, internal ->
            identityManager.getBitcoinAddress(type, index, internal)
        }

    /**
     * The addresses the background watcher polls (P0.4): the next receive index
     * plus reserved indices, both types. A 5-minute poll does not need the whole
     * gap window — old addresses are covered by [loadState].
     */
    fun watcherAddresses(): List<String> {
        val pointers = currentPointers()
        val indices = (setOf(pickReceiveIndex(pointers)) + pointers.reserved).sorted()
        return indices.flatMap { index ->
            BitcoinAddressType.entries.map { type -> identityManager.getBitcoinAddress(type, index) }
        }.distinct()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    /**
     * Load balance + history for the HD scan window.
     *
     * Fresh cache hits are free; the remaining addresses are fetched with
     * bounded concurrency ([SCAN_CONCURRENCY]) — a strict serial scan cost tens
     * of seconds on a high-RTT link (measured ~35 s, 2026-09-17). Accumulation
     * stays sequential so totals/pointer advancement are deterministic. A
     * partial failure never blanks the screen: the last known value for that
     * address is used and the result is flagged [WalletState.stale].
     */
    suspend fun loadState(): Result<WalletState> = withContext(Dispatchers.IO) {
        try {
            val scanned = scanSetAddresses()
            val now = System.currentTimeMillis()
            val fresh = ConcurrentHashMap<String, AddressScan>()
            val toFetch = mutableListOf<ScannedAddress>()
            for (sa in scanned) {
                val cached = scanCache[sa.address]
                if (cached != null && now - cached.atMs < SCAN_CACHE_TTL_MS) {
                    fresh[sa.address] = cached.scan
                } else {
                    toFetch.add(sa)
                }
            }

            val fetched = ConcurrentHashMap<String, AddressScan?>()
            toFetch.chunked(SCAN_CONCURRENCY).forEach { chunk ->
                coroutineScope {
                    chunk.map { sa -> async { fetched[sa.address] = scanAddress(sa) } }.awaitAll()
                }
                delay(SCAN_BATCH_DELAY_MS)
            }

            var confirmed = 0L
            var unconfirmed = 0L
            val allTxs = mutableListOf<ChainMonitor.AddressTx>()
            val activeAddresses = mutableSetOf<String>()
            var fetchedAny = false
            var stale = false
            for (sa in scanned) {
                val served = fresh[sa.address] ?: fetched[sa.address]
                if (fresh.containsKey(sa.address) || fetched[sa.address] != null) {
                    fetchedAny = true
                }
                if (served != null) {
                    confirmed += served.confirmedSats
                    unconfirmed += served.unconfirmedSats
                    allTxs.addAll(served.txs)
                    if (served.isActive()) activeAddresses.add(sa.address)
                    continue
                }
                // Partial failure: fall back to the last known value rather
                // than blanking the whole screen. Never a fake zero.
                val cached = scanCache[sa.address]
                if (cached != null) {
                    confirmed += cached.scan.confirmedSats
                    unconfirmed += cached.scan.unconfirmedSats
                    allTxs.addAll(cached.scan.txs)
                    if (cached.scan.isActive()) activeAddresses.add(sa.address)
                    fetchedAny = true
                    stale = true
                }
            }
            // Fail only when NOTHING could be served (fresh install + dead
            // explorer): a stale-marked real balance is not a fake zero.
            if (!fetchedAny) {
                return@withContext Result.failure(
                    Exception("Could not fetch wallet balance")
                )
            }
            // P0.8: advance the external pointer from observed on-chain usage.
            // A partial/stale scan advances NOTHING (never slide on a 429).
            if (!stale) {
                advanceExternalPointer(scanned, activeAddresses)
            }
            val deduped = allTxs
                .distinctBy { it.txid }
                .sortedByDescending { it.blockTimeSec }
            snapshotStore.save(
                WalletSnapshotStore.Snapshot(confirmed, unconfirmed, deduped),
                currentIdentityKey()
            )
            Result.success(
                WalletState(
                    addresses = myAddresses(),
                    confirmedSats = confirmed,
                    unconfirmedSats = unconfirmed,
                    txs = deduped,
                    stale = stale
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet state", e)
            Result.failure(e)
        }
    }

    /** Fetch + cache one scanned address. Null means "no answer" (fail closed). */
    private suspend fun scanAddress(sa: ScannedAddress): AddressScan? {
        val info = chainMonitor.getAddressInfo(sa.address).getOrNull() ?: return null
        val used = info.txCount > 0L ||
            info.confirmedBalanceSats != 0L ||
            info.unconfirmedBalanceSats != 0L
        val txs = if (used) {
            chainMonitor.getAddressTxs(sa.address)
                .getOrElse { scanCache[sa.address]?.scan?.txs ?: emptyList() }
        } else {
            emptyList()
        }
        val scan = AddressScan(info.confirmedBalanceSats, info.unconfirmedBalanceSats, txs)
        scanCache[sa.address] = ScanCacheEntry(scan, System.currentTimeMillis())
        return scan
    }

    /**
     * Last persisted balance/history for this identity, or null when nothing is
     * stored. Renders the wallet instantly on a cold open; the caller still
     * refreshes live in the background.
     */
    fun cachedState(): WalletState? {
        val snapshot = snapshotStore.load(currentIdentityKey()) ?: return null
        return WalletState(
            addresses = myAddresses(),
            confirmedSats = snapshot.confirmedSats,
            unconfirmedSats = snapshot.unconfirmedSats,
            txs = snapshot.txs,
            stale = true
        )
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
        fromType: BitcoinAddressType? = null,
        tier: WalletFeePolicy.FeeTier = WalletFeePolicy.FeeTier.FAST,
        customRate: Long? = null
    ): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val coins = collectSpendableCoins(fromType)
                if (coins.isEmpty()) {
                    throw IllegalStateException("No confirmed UTXOs to estimate fee")
                }
                val feeRate = WalletFeePolicy.rateFor(chainMonitor.estimateFees(), tier, customRate)
                CoinSelector.select(coins, amountSats, feeRate).feeSats
            }
        }

    /**
     * Collect confirmed UTXOs from the SINGLE scan set (P0.5), tagged with the
     * exact `{type, index, internal}` of the address they sit on so each input
     * is later signed with its own key (P0.6).
     *
     * These two loops (`estimateSendFee` / `send`) are the ONLY callers of
     * [ChainMonitor.getAddressUtxos] — if this set omits the internal change
     * chain, every send after the first strands its change.
     */
    private suspend fun collectSpendableCoins(fromType: BitcoinAddressType?): List<SelectedCoin> {
        val coins = mutableListOf<SelectedCoin>()
        for (sa in scanSetAddresses()) {
            if (fromType != null && sa.type != fromType) continue
            chainMonitor.getAddressUtxos(sa.address).getOrThrow()
                .forEach { coins.add(SelectedCoin(sa.type, sa.index, sa.internal, it)) }
        }
        return coins
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
        maxFeeSats: Long? = null,
        tier: WalletFeePolicy.FeeTier = WalletFeePolicy.FeeTier.FAST,
        customRate: Long? = null
    ): Result<SendResult> =
        withContext(Dispatchers.IO) {
            // F6 (2026-09-23): the single broadcast chokepoint for both the
            // personal wallet and escrow funding — fail closed on an attached
            // debugger (tampering) and on a forked fee wallet.
            if (RuntimeIntegrity.blocked(integrityProbe.assess())) {
                return@withContext Result.failure(
                    IllegalStateException(RuntimeIntegrity.ERR_RUNTIME_INTEGRITY)
                )
            }
            if (!NeoP2PConfig.verifyFeeWalletIntegrity()) {
                return@withContext Result.failure(IllegalStateException("ERR_CONFIG_INTEGRITY"))
            }
            try {
                val destination = try {
                    Address.fromString(params, toAddress)
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        Exception("Invalid destination address: $toAddress")
                    )
                }
                // P3.1: reject Taproot / unknown witness-version destinations.
                // A P2TR output is 43 vB (vs 34 assumed by the fee model) and
                // NEO-P2P has no tapscript policy.
                if (destination is SegwitAddress && destination.witnessVersion >= 1) {
                    return@withContext Result.failure(
                        Exception("Unsupported address type (Taproot/unknown witness version): $toAddress")
                    )
                }
                val taggedUtxos = try {
                    collectSpendableCoins(fromType)
                } catch (e: Exception) {
                    return@withContext Result.failure(Exception("Could not fetch UTXOs"))
                }
                if (taggedUtxos.isEmpty()) {
                    return@withContext Result.failure(
                        if (fromType != null)
                            Exception("No confirmed ${fromType.name.lowercase()} balance to send")
                        else
                            Exception("No confirmed balance to send")
                    )
                }

                val feeRate = WalletFeePolicy.rateFor(chainMonitor.estimateFees(), tier, customRate)
                val spend = CoinSelector.select(taggedUtxos, amountSats, feeRate)
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
                val chosen = CoinOrder.apply(spend.chosen)
                val feeSats = spend.feeSats
                val selected = spend.selectedSats

                val tx = Transaction(params)
                // P2.3 anti-fee-sniping: locktime = current tip (0 when unknown).
                tx.setLockTime(lockTimeFor(chainMonitor.tipHeight()))
                for (coin in chosen) {
                    tx.addInput(
                        Sha256Hash.wrap(coin.utxo.txid),
                        coin.utxo.vout.toLong(),
                        ScriptBuilder.createEmpty()
                    )
                }
                tx.addOutput(Coin.valueOf(amountSats), destination)
                val change = selected - amountSats - feeSats
                // Change goes to the next internal (p2wpkh) index (P0.6); the
                // pointer is persisted write-ahead immediately before broadcast.
                val pointers = currentPointers()
                val changeIndex = pickChangeIndex(pointers)
                val emitsChange = change > DUST_THRESHOLD_SATS
                if (emitsChange) {
                    val changeAddress = identityManager.getBitcoinAddress(
                        BitcoinAddressType.SEGWIT, changeIndex, internal = true
                    )
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

                // Sign every input with the key of the index it belongs to
                // (P0.6). P2PKH inputs use the legacy sighash against the P2PKH
                // output script; P2WPKH inputs use the BIP-143 witness sighash
                // (value-committed) and put the signature in the witness.
                for (i in tx.inputs.indices) {
                    val coin = chosen[i]
                    val privBytes = identityManager.getBitcoinPrivateKeyBytes(coin.index, coin.internal)
                    val key = try {
                        ECKey.fromPrivate(privBytes)
                    } finally {
                        // ECKey.fromPrivate copies the scalar into its own
                        // BigInteger; the raw array is ours to wipe (audit P3-4).
                        privBytes.fill(0)
                    }
                    if (coin.type == BitcoinAddressType.LEGACY) {
                        val address = identityManager.getBitcoinAddress(
                            coin.type, coin.index, coin.internal
                        )
                        val outputScript = ScriptBuilder.createOutputScript(LegacyAddress.fromBase58(params, address))
                        val hash = tx.hashForSignature(i, outputScript, Transaction.SigHash.ALL, false)
                        val sig = key.sign(hash)
                        val sigEncoded = sig.encodeToDER() + byteArrayOf(Transaction.SigHash.ALL.value.toByte())
                        val txSig = org.bitcoinj.crypto.TransactionSignature.decodeFromBitcoin(sigEncoded, false, false)
                        tx.replaceInput(i, tx.getInput(i.toLong()).withScriptSig(ScriptBuilder.createInputScript(txSig, key)))
                    } else {
                        // BIP-143: the scriptCode committed in the witness sighash
                        // for P2WPKH is the *P2PKH script*, NOT the OP_0 <hash160>
                        // output script (see WalletSegwitSigningTest).
                        val scriptCode = ScriptBuilder.createP2PKHOutputScript(key)
                        val inputValue = Coin.valueOf(coin.utxo.valueSats)
                        val txSig = tx.calculateWitnessSignature(i, key, scriptCode, inputValue, Transaction.SigHash.ALL, false)
                        val witness = TransactionWitness.redeemP2WPKH(txSig, key)
                        tx.replaceInput(i, tx.getInput(i.toLong()).withWitness(witness))
                    }
                }

                // P0.6 write-ahead: persist the advanced change pointer BEFORE
                // broadcasting, so a kill between broadcast and persist cannot
                // reuse the index. On broadcast failure, revert it.
                if (emitsChange) {
                    val advanced = pointers.copy(nextChange = changeIndex + 1)
                    if (!savePointers(advanced)) {
                        return@withContext Result.failure(
                            IllegalStateException("Could not persist wallet state")
                        )
                    }
                }

                // Audit P2-1 (2026-09-12): compute our own txid and require the
                // explorer to echo it back, so a wrong/forged txid can never be
                // shown to the user or stored on an escrow.
                val localTxid = tx.getTxId().toString()
                val txHex = tx.bitcoinSerialize().joinToString("") { "%02x".format(it) }
                val txid = chainMonitor.broadcastTx(txHex, localTxid).getOrElse {
                    if (emitsChange) savePointers(pointers)
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

/** A confirmed UTXO tagged with the exact address (type + index + chain) it came from. */
data class SelectedCoin(
    val type: BitcoinAddressType,
    val index: Int,
    val internal: Boolean,
    val utxo: ChainMonitor.Utxo
)

/** Result of coin selection ([CoinSelector]): the chosen inputs, the real fee, and the sum selected. */
data class SelectedSpend(
    val chosen: List<SelectedCoin>,
    val feeSats: Long,
    val selectedSats: Long
)
