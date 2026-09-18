package com.neop2p.data.wallet

import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.domain.model.BitcoinAddressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoinSelectorTest {

    private fun coin(
        sats: Long,
        type: BitcoinAddressType = BitcoinAddressType.SEGWIT,
        index: Int = 0,
        internal: Boolean = false,
        txid: String = "t$index"
    ) = SelectedCoin(type, index, internal, ChainMonitor.Utxo(txid, 0, sats))

    @Test
    fun `effective value subtracts the input cost`() {
        assertEquals(99_320L, CoinSelector.effectiveValue(100_000L, 680L))
    }

    @Test
    fun `exact changeless match gives the slack to the miner`() {
        // target = 100_000 + (34+10)*10 = 100_440; effective = 101_120 - 680 = 100_440.
        val spend = CoinSelector.select(listOf(coin(101_120L)), 100_000L, 10L)
        assertEquals(1, spend.chosen.size)
        assertEquals(101_120L, spend.selectedSats)
        assertEquals(1_120L, spend.feeSats)
    }

    @Test
    fun `oversized coin produces change and the normal fee`() {
        val spend = CoinSelector.select(listOf(coin(200_000L)), 100_000L, 10L)
        assertEquals(1, spend.chosen.size)
        // 68 input + 34 output + 10 overhead = 112 vB × 10 = 1120.
        assertEquals(1_120L, spend.feeSats)
        assertEquals(200_000L, spend.selectedSats)
    }

    @Test
    fun `dust change is folded into the fee`() {
        val spend = CoinSelector.select(listOf(coin(101_600L)), 100_000L, 10L)
        // change would be 480 (<= dust) so it is given to the miner.
        assertEquals(101_600L, spend.selectedSats)
        assertEquals(1_600L, spend.feeSats)
    }

    @Test
    fun `mixed legacy and segwit inputs pay for every input`() {
        val coins = listOf(
            coin(60_000L, BitcoinAddressType.LEGACY, 0),
            coin(60_000L, BitcoinAddressType.LEGACY, 1),
            coin(50_000L, BitcoinAddressType.SEGWIT, 2)
        )
        val spend = CoinSelector.select(coins, 100_000L, 10L)
        assertTrue(spend.selectedSats >= 100_000L)
        assertTrue(spend.feeSats >= WalletService.MIN_WALLET_FEE_SATS)
    }

    @Test
    fun `insufficient balance returns a partial selection for the caller to reject`() {
        val spend = CoinSelector.select(listOf(coin(10_000L)), 100_000L, 10L)
        assertEquals(1, spend.chosen.size)
        assertEquals(10_000L, spend.selectedSats)
        assertTrue(spend.selectedSats < 100_000L + spend.feeSats)
    }

    @Test
    fun `legacy-first regression is fixed`() {
        // The old greedy loop guessed the fee from 1 segwit input, broke after
        // the 101_500 legacy coin, then found the legacy fee (2230) exceeded
        // the selection — "Insufficient balance" with 103_500 available.
        val coins = listOf(
            coin(101_500L, BitcoinAddressType.LEGACY, 0),
            coin(1_000L, BitcoinAddressType.SEGWIT, 1),
            coin(1_000L, BitcoinAddressType.SEGWIT, 2)
        )
        val spend = CoinSelector.select(coins, 100_000L, 10L)
        assertEquals(103_500L, spend.selectedSats)
        assertTrue(spend.selectedSats >= 100_000L + spend.feeSats)
    }

    @Test
    fun `change is never emitted below dust`() {
        for (value in 100_500L..103_000L) {
            val spend = CoinSelector.select(listOf(coin(value)), 100_000L, 10L)
            val change = spend.selectedSats - 100_000L - spend.feeSats
            assertTrue(
                "value=$value produced sub-dust change $change",
                change <= 0L || change > CoinSelector.DUST_THRESHOLD_SATS
            )
        }
    }

    @Test
    fun `seeded property checks hold across many fixtures`() {
        for (seed in 0 until 200) {
            val rng = Random(seed)
            val coins = (0 until rng.nextInt(1, 8)).map { i ->
                coin(
                    rng.nextLong(2_000L, 300_000L),
                    if (rng.nextBoolean()) BitcoinAddressType.LEGACY else BitcoinAddressType.SEGWIT,
                    index = i
                )
            }
            val amount = rng.nextLong(1L, 250_000L)
            val spend = CoinSelector.select(coins, amount, rng.nextLong(2L, 30L), random = Random(seed))
            // Chosen set is exact and non-empty.
            assertEquals(spend.chosen.sumOf { it.utxo.valueSats }, spend.selectedSats)
            assertTrue(spend.chosen.isNotEmpty())
            // Sufficient selections always pay at least the fee floor.
            if (spend.selectedSats >= amount + spend.feeSats) {
                assertTrue(spend.feeSats >= WalletService.MIN_WALLET_FEE_SATS)
            }
            // No sub-dust change.
            val change = spend.selectedSats - amount - spend.feeSats
            assertTrue(change <= 0L || change > CoinSelector.DUST_THRESHOLD_SATS)
        }
    }

    @Test(timeout = 10_000)
    fun `three hundred inputs terminate`() {
        val rng = Random(7)
        val coins = (0 until 300).map { i ->
            coin(rng.nextLong(5_000L, 20_000L), index = i)
        }
        // 1234567 cannot be hit exactly by these multiples, forcing BnB to bail
        // through the tries cap and fall back.
        val spend = CoinSelector.select(coins, 1_234_567L, 5L, random = Random(7))
        assertTrue(spend.selectedSats > 0L)
        assertTrue(spend.chosen.isNotEmpty())
    }

    @Test
    fun `branchAndBound finds a subset inside the window`() {
        val eff = longArrayOf(8L, 3L, 5L, 9L)
        val subset = CoinSelector.branchAndBound(eff, target = 13L, window = 2L)
        assertTrue(subset != null)
        val sum = subset!!.sumOf { eff[it] }
        assertTrue(sum in 13L..15L)
    }

    @Test
    fun `branchAndBound prunes an unmatchable target`() {
        val eff = longArrayOf(100L, 200L, 300L)
        assertEquals(null, CoinSelector.branchAndBound(eff, target = 1_000L, window = 1L))
    }
}
