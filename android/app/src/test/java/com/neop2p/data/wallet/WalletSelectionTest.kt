package com.neop2p.data.wallet

import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.domain.model.BitcoinAddressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.2 — the selection the service uses is [CoinSelector]. The old greedy
 * `selectSpend` was deleted, so these assert the selector's contract through
 * the same scenarios (mixed chains, fee floor, shortfalls) and that a chose
 * coin carries the `{type, index, internal}` the signing loop needs.
 */
class WalletSelectionTest {

    private fun coin(
        type: BitcoinAddressType,
        sats: Long,
        txid: String = "a",
        index: Int = 0,
        internal: Boolean = false
    ) = SelectedCoin(type, index, internal, ChainMonitor.Utxo(txid, 0, sats))

    @Test
    fun `single segwit input fee uses one input vsize`() {
        val spend = CoinSelector.select(listOf(coin(BitcoinAddressType.SEGWIT, 200_000L)), 100_000L, 10L)
        assertEquals(1, spend.chosen.size)
        // 68 (input) + 34 (send out) + 10 (overhead) = 112 vB × 10 = 1120.
        assertEquals(1_120L, spend.feeSats)
        assertEquals(200_000L, spend.selectedSats)
    }

    @Test
    fun `two legacy inputs fee charges both inputs`() {
        val coins = listOf(
            coin(BitcoinAddressType.LEGACY, 60_000L, txid = "a"),
            coin(BitcoinAddressType.LEGACY, 60_000L, txid = "b")
        )
        val spend = CoinSelector.select(coins, 100_000L, 10L)
        assertEquals(2, spend.chosen.size)
        // 148×2 + 34 + 10 = 340 vB × 10 = 3400
        assertEquals(3_400L, spend.feeSats)
    }

    @Test
    fun `fee floor applies at low rates`() {
        val spend = CoinSelector.select(listOf(coin(BitcoinAddressType.SEGWIT, 200_000L)), 100_000L, 1L)
        assertEquals(250L, spend.feeSats)
    }

    @Test
    fun `insufficient utxos return partial selection for caller to reject`() {
        val spend = CoinSelector.select(listOf(coin(BitcoinAddressType.SEGWIT, 10_000L)), 100_000L, 10L)
        assertEquals(1, spend.chosen.size)
        assertEquals(10_000L, spend.selectedSats)
        // send() checks selectedSats >= amountSats + feeSats and fails with
        // "Insufficient balance".
        assertTrue(spend.selectedSats < 100_000L + spend.feeSats)
    }

    @Test
    fun `chosen coins carry their owning index and chain`() {
        val coins = listOf(
            coin(BitcoinAddressType.LEGACY, 60_000L, txid = "a", index = 4),
            coin(BitcoinAddressType.SEGWIT, 60_000L, txid = "b", index = 7, internal = true)
        )
        val spend = CoinSelector.select(coins, 100_000L, 10L)
        assertEquals(2, spend.chosen.size)
        val indices = spend.chosen.map { it.index }.toSet()
        assertTrue(indices.containsAll(setOf(4, 7)))
        assertTrue(spend.chosen.any { it.internal && it.index == 7 && it.type == BitcoinAddressType.SEGWIT })
    }
}
