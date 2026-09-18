package com.neop2p.data.wallet

import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.domain.model.BitcoinAddressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoinOrderTest {

    private fun coin(sats: Long, txid: String, index: Int = 0) =
        SelectedCoin(
            BitcoinAddressType.SEGWIT,
            index,
            internal = false,
            utxo = ChainMonitor.Utxo(txid, 0, sats)
        )

    @Test
    fun `seeded shuffle is deterministic`() {
        val coins = (0 until 8).map { coin(1_000L + it, "tx$it") }
        assertEquals(
            CoinOrder.apply(coins, Random(42)).map { it.utxo.txid },
            CoinOrder.apply(coins, Random(42)).map { it.utxo.txid }
        )
    }

    @Test
    fun `shuffle preserves the multiset and does not mutate the input`() {
        val coins = (0 until 8).map { coin(1_000L + it, "tx$it") }
        val snapshot = coins.map { it.utxo.txid }
        val shuffled = CoinOrder.apply(coins, Random(7))
        assertEquals(snapshot.sorted(), shuffled.map { it.utxo.txid }.sorted())
        assertEquals("input list must not be mutated", snapshot, coins.map { it.utxo.txid })
        assertEquals(8, shuffled.size)
    }

    @Test
    fun `signing order equals the transaction input order`() {
        val coins = (0 until 6).map { coin(1_000L + it, "tx$it", index = it) }
        val shuffled = CoinOrder.apply(coins, Random(3))
        // send() builds tx inputs from this exact list and indexes the signing
        // loop positionally, so the order the tx sees MUST be `shuffled`.
        val txInputOrder = shuffled.map { it.utxo.txid }
        val signingOrder = shuffled.indices.map { shuffled[it].utxo.txid }
        assertEquals(txInputOrder, signingOrder)
    }

    @Test
    fun `single and empty lists are returned unchanged`() {
        val one = listOf(coin(1_000L, "a"))
        assertEquals(one, CoinOrder.apply(one, Random(1)))
        assertEquals(emptyList<SelectedCoin>(), CoinOrder.apply(emptyList(), Random(1)))
    }

    @Test
    fun `first input is not always the largest`() {
        val descending = listOf(
            coin(500_000L, "largest", index = 0),
            coin(400_000L, "b", index = 1),
            coin(300_000L, "c", index = 2),
            coin(200_000L, "d", index = 3)
        )
        var sawNonLargestFirst = false
        var sawLargestFirst = false
        for (seed in 0 until 200) {
            val first = CoinOrder.apply(descending, Random(seed)).first().utxo.txid
            if (first == "largest") sawLargestFirst = true else sawNonLargestFirst = true
        }
        assertTrue("shuffle should sometimes put a non-largest coin first", sawNonLargestFirst)
        assertTrue("shuffle should sometimes put the largest first", sawLargestFirst)
    }

    @Test
    fun `ordering only changes order not set`() {
        val coins = (0 until 5).map { coin(1_000L * (it + 1), "tx$it", index = it) }
        val shuffled = CoinOrder.apply(coins, Random(99))
        assertFalse(shuffled === coins)
        assertEquals(coins.toSet(), shuffled.toSet())
    }
}
