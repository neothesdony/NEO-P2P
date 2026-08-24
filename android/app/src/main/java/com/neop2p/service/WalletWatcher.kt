package com.neop2p.service

import android.util.Log
import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.data.wallet.WalletService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodically polls the user's BIP-44 wallet address for incoming Bitcoin and
 * emits a notification for each new RECEIVE transaction it has not seen before.
 *
 * This is the missing *producer* for wallet-receive notifications:
 * [WalletService.loadState] is pull-on-demand (called when the wallet screen
 * opens), so nothing emits in the background. This watcher closes that gap.
 *
 * Deduplication is in-memory for the process lifetime (the foreground service
 * that owns this scope keeps the app alive, so a session-restart reset is
 * acceptable). On the first poll after app start, only txs not already seen
 * this process are notified.
 */
@Singleton
class WalletWatcher @Inject constructor(
    private val walletService: WalletService,
    private val chainMonitor: ChainMonitor,
    private val notificationDispatcher: NotificationDispatcher
) {
    companion object {
        private const val TAG = "WalletWatcher"
        private const val POLL_INTERVAL_MS = 60_000L  // 60s between polls
        private const val INITIAL_DELAY_MS = 5_000L   // let the identity come up
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
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Wallet poll failed: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }
}
