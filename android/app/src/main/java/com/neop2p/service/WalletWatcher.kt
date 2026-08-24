package com.neop2p.service

import android.content.Context
import android.util.Log
import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.data.wallet.WalletService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray

/**
 * Periodically polls the user's BIP-44 wallet address for incoming Bitcoin and
 * emits a notification for each new RECEIVE transaction it has not seen before.
 *
 * This is the missing *producer* for wallet-receive notifications:
 * [WalletService.loadState] is pull-on-demand (called when the wallet screen
 * opens), so nothing emits in the background. This watcher closes that gap.
 *
 * Deduplication is persisted in SharedPreferences (last-seen txids), so a
 * process restart does NOT replay old receives as fresh notifications.
 */
@Singleton
class WalletWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletService: WalletService,
    private val chainMonitor: ChainMonitor,
    private val notificationDispatcher: NotificationDispatcher
) {
    companion object {
        private const val TAG = "WalletWatcher"
        private const val POLL_INTERVAL_MS = 60_000L  // 60s between polls
        private const val INITIAL_DELAY_MS = 5_000L   // let the identity come up

        private const val PREFS_NAME = "neop2p_wallet_watch"
        private const val KEY_SEEN_TXIDS = "seen_txids"
    }

    private val seenTxids = mutableSetOf<String>()

    /** Emits the txid + sats for each newly-detected incoming receive. */
    private val _receives = MutableStateFlow<Pair<String, Long>?>(null)
    val receives: StateFlow<Pair<String, Long>?> = _receives.asStateFlow()

    private var started = false

    /** Start the periodic poller. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        loadSeenTxids()
        scope.launch {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                try {
                    val address = walletService.myAddress()
                    chainMonitor.getAddressTxs(address).onSuccess { txs ->
                        // Only newly-seen RECEIVE txs should notify.
                        txs.filter { it.direction == ChainMonitor.TxDirection.RECEIVE }
                            .forEach { tx ->
                                if (seenTxids.add(tx.txid)) {
                                    notificationDispatcher.notifyWalletReceive(tx.txid, tx.receivedSats)
                                    _receives.value = tx.txid to tx.receivedSats
                                }
                            }
                        persistSeenTxids()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Wallet poll failed: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun loadSeenTxids() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_SEEN_TXIDS, null) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { seenTxids.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load seen txids: ${e.message}")
        }
    }

    private fun persistSeenTxids() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = JSONArray()
            seenTxids.forEach { arr.put(it) }
            prefs.edit().putString(KEY_SEEN_TXIDS, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist seen txids: ${e.message}")
        }
    }
}
