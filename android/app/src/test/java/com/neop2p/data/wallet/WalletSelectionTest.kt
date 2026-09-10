package com.neop2p.data.wallet

import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.domain.model.BitcoinAddressType
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletSelectionTest {

    private fun utxo(txid: String, vout: Int, sats: Long) = ChainMonitor.Utxo(txid, vout, sats)

    @Test
    fun `single segwit input fee uses one input vsize`() {
        val utxos = listOf(BitcoinAddressType.SEGWIT to utxo("a", 0, 200_000L))
        val spend = selectSpend(utxos, 100_000L, feeRate = 10L)
        assertEquals(1, spend.chosen.size)
        // 68 (input) + 34 (send out) + 31 (change out) + 10 (overhead) = 143 vB × 10 = 1430
        assertEquals(1430L, spend.feeSats)
        assertEquals(200_000L, spend.selectedSats)
    }

    @Test
    fun `two legacy inputs fee charges both inputs`() {
        val utxos = listOf(
            BitcoinAddressType.LEGACY to utxo("a", 0, 60_000L),
            BitcoinAddressType.LEGACY to utxo("b", 0, 60_000L)
        )
        val spend = selectSpend(utxos, 100_000L, feeRate = 10L)
        assertEquals(2, spend.chosen.size)
        // 148×2 + 34 + 31 + 10 = 371 vB × 10 = 3710
        assertEquals(3710L, spend.feeSats)
    }

    @Test
    fun `selection stops once amount plus fee is covered`() {
        val utxos = listOf(
            BitcoinAddressType.SEGWIT to utxo("a", 0, 100_000L),
            BitcoinAddressType.SEGWIT to utxo("b", 0, 100_000L)
        )
        val spend = selectSpend(utxos, 50_000L, feeRate = 10L)
        assertEquals(1, spend.chosen.size)
    }

    @Test
    fun `fee floor applies at low rates`() {
        val utxos = listOf(BitcoinAddressType.SEGWIT to utxo("a", 0, 200_000L))
        val spend = selectSpend(utxos, 100_000L, feeRate = 1L)
        assertEquals(250L, spend.feeSats)
    }

    @Test
    fun `insufficient utxos return partial selection for caller to reject`() {
        val utxos = listOf(BitcoinAddressType.SEGWIT to utxo("a", 0, 10_000L))
        val spend = selectSpend(utxos, 100_000L, feeRate = 10L)
        assertEquals(1, spend.chosen.size)
        assertEquals(10_000L, spend.selectedSats)
        // send() checks selectedSats >= amountSats + feeSats and fails with
        // "Insufficient balance" — the estimate path just shows the fee.
    }
}
