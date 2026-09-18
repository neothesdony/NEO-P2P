package com.neop2p.data.wallet

import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Randomizes the input order of a transaction (P1.3), mirroring Cake Wallet's
 * per-build secure-RNG coin ordering (`cw_bitcoin/lib/electrum_wallet.dart`).
 *
 * Deterministic input ordering leaks wallet fingerprint: address-scan heuristics
 * correlate the order in which a wallet's UTXOs appear across transactions.
 * Shuffling once per build (the SAME list drives `addInput` and signing) makes
 * that correlation useless.
 *
 * Ordering ONLY: the selected SET is never changed.
 */
object CoinOrder {

    private val secureRandom: Random by lazy {
        java.security.SecureRandom().asKotlinRandom()
    }

    /**
     * Returns a shuffled COPY of [coins] (the input list is not mutated). A
     * 0- or 1-element list is returned as-is. [random] is injectable so tests
     * can seed it.
     */
    fun apply(coins: List<SelectedCoin>, random: Random = secureRandom): List<SelectedCoin> {
        if (coins.size < 2) return coins
        val out = coins.toMutableList()
        for (i in out.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = out[i]
            out[i] = out[j]
            out[j] = tmp
        }
        return out
    }
}
