package com.neop2p.data.wallet

import com.neop2p.domain.model.BitcoinAddressType
import kotlin.random.Random

/**
 * Pure changeless-aware coin selection (P1.1).
 *
 * Ported from Cake Wallet's `cw_bitcoin/lib/coin_selection.dart` (branch-and-
 * bound over *effective values*), replacing the old greedy largest-first
 * `selectSpend`, which carried a live defect: it guessed the fee from a single
 * SegWit input, then recomputed for the actual mix — a legacy input (148 vB vs
 * 68 vB) could push the real fee above the selection and fail the send with
 * "Insufficient balance" while funds were available.
 *
 * Effective value = `value - inputCost`; the BnB target is
 * `amount + (output + overhead) * rate`; a subset is accepted changeless when
 * its effective sum lands in `[target, target + costOfChange]` — the leftover
 * is cheaper to give the miner than to add a change output.
 *
 * P2TR destinations are rejected (P3.1 decision), so 34 vB stays the maximum
 * output size. If that decision is ever reversed, this bound must become 43.
 */
object CoinSelector {

    /** Max non-change output vsize. P2TR is rejected, so P2PKH (34 vB) is the bound. */
    const val MAX_OUTPUT_VSIZE: Long = WalletService.P2PKH_OUTPUT_VSIZE

    const val DUST_THRESHOLD_SATS: Long = 546L

    /** DFS visit bound: caps the 2^n worst case. */
    const val BNB_MAX_TRIES: Int = 100_000

    /** How many random draws to try before the accumulative fallback. */
    private const val RANDOM_DRAWS = 200

    fun select(
        coins: List<SelectedCoin>,
        amountSats: Long,
        feeRate: Long,
        dustThreshold: Long = DUST_THRESHOLD_SATS,
        changeVsize: Long = BitcoinAddressType.SEGWIT.outputVsize,
        random: Random = Random.Default
    ): SelectedSpend {
        if (coins.isEmpty()) return SelectedSpend(emptyList(), 0L, 0L)
        val rate = WalletFeePolicy.clampRate(feeRate)
        val overhead = (MAX_OUTPUT_VSIZE + WalletService.FIXED_OVERHEAD_VSIZE) * rate
        val target = amountSats + overhead
        val changeCost = changeVsize * rate
        val effective = LongArray(coins.size) { coins[it].utxo.valueSats - coins[it].type.inputVsize * rate }

        // 1. Changeless branch-and-bound: slack up to the cost of change.
        branchAndBound(effective, target, changeCost)?.let { idx ->
            return build(coins, idx, amountSats, rate, dustThreshold, changeless = true)
        }
        // 2. Near-exact changeless match: slack up to dust.
        if (dustThreshold > changeCost) {
            branchAndBound(effective, target, dustThreshold)?.let { idx ->
                return build(coins, idx, amountSats, rate, dustThreshold, changeless = true)
            }
        }
        // 3. Single random draw.
        singleRandomDraw(coins, effective, target, changeCost, random)?.let { idx ->
            return build(coins, idx, amountSats, rate, dustThreshold, changeless = false)
        }
        // 4. Accumulative fallback (never fails to return; the caller rejects shortfalls).
        return build(coins, accumulative(coins, effective, target, changeCost), amountSats, rate, dustThreshold, changeless = false)
    }

    /**
     * Effective value of one coin: what it contributes after paying its own
     * input fee. Non-positive coins are uneconomical to spend.
     */
    fun effectiveValue(valueSats: Long, inputCostSats: Long): Long = valueSats - inputCostSats

    /**
     * Subset-sum over [eff] accepted when the sum lands in
     * `[target, target + window]`. Returns the ORIGINAL coin indices, or null.
     * Sorted descending with suffix-sum pruning, bounded by [maxTries].
     */
    fun branchAndBound(
        eff: LongArray,
        target: Long,
        window: Long,
        maxTries: Int = BNB_MAX_TRIES
    ): List<Int>? {
        if (target <= 0L) return emptyList()
        val upper = target + window
        val candidates = eff.indices.filter { eff[it] > 0L }.sortedByDescending { eff[it] }
        if (candidates.isEmpty()) return null
        val sortedEff = LongArray(candidates.size) { eff[candidates[it]] }
        val suffix = LongArray(candidates.size + 1)
        for (i in candidates.size - 1 downTo 0) suffix[i] = suffix[i + 1] + sortedEff[i]
        if (suffix[0] < target) return null

        var tries = 0
        val current = ArrayList<Int>()
        var found: List<Int>? = null

        fun dfs(i: Int, sum: Long) {
            if (found != null) return
            if (sum in target..upper) {
                found = current.toList()
                return
            }
            if (i >= candidates.size) return
            if (sum > upper) return
            if (sum + suffix[i] < target) return
            if (tries++ >= maxTries) return
            current.add(candidates[i])
            dfs(i + 1, sum + sortedEff[i])
            if (found != null) return
            current.removeAt(current.size - 1)
            dfs(i + 1, sum)
        }

        dfs(0, 0L)
        return found
    }

    private fun singleRandomDraw(
        coins: List<SelectedCoin>,
        eff: LongArray,
        target: Long,
        changeCost: Long,
        random: Random
    ): List<Int>? {
        val goal = target + changeCost
        repeat(RANDOM_DRAWS) {
            val order = coins.indices.shuffled(random)
            val chosen = ArrayList<Int>()
            var sum = 0L
            for (i in order) {
                chosen.add(i)
                sum += eff[i]
                if (sum >= goal) return chosen
            }
        }
        return null
    }

    private fun accumulative(
        coins: List<SelectedCoin>,
        eff: LongArray,
        target: Long,
        changeCost: Long
    ): List<Int> {
        val goal = target + changeCost
        val chosen = ArrayList<Int>()
        var sum = 0L
        for (i in coins.indices.sortedByDescending { eff[it] }) {
            chosen.add(i)
            sum += eff[i]
            if (sum >= goal) break
        }
        return chosen
    }

    private fun build(
        coins: List<SelectedCoin>,
        indices: List<Int>,
        amountSats: Long,
        rate: Long,
        dustThreshold: Long,
        changeless: Boolean
    ): SelectedSpend {
        val chosen = indices.map { coins[it] }
        val selected = chosen.sumOf { it.utxo.valueSats }
        val vsize = chosen.sumOf { it.type.inputVsize } +
            MAX_OUTPUT_VSIZE + WalletService.FIXED_OVERHEAD_VSIZE
        val baseFee = maxOf(vsize * rate, WalletService.MIN_WALLET_FEE_SATS)
        // Changeless: all slack goes to the miner, no change output.
        if (changeless && selected - amountSats >= WalletService.MIN_WALLET_FEE_SATS) {
            return SelectedSpend(chosen, selected - amountSats, selected)
        }
        val change = selected - amountSats - baseFee
        // Dust change is folded into the fee (the miner collects it) — never a
        // sub-dust output.
        val fee = if (change in 1..dustThreshold) baseFee + change else baseFee
        return SelectedSpend(chosen, fee, selected)
    }
}
