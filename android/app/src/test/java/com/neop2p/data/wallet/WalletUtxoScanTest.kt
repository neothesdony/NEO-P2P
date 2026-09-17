package com.neop2p.data.wallet

import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.domain.model.BitcoinAddressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.5 — the UTXO source is the scan set.
 *
 * There is no mocking framework in the build and a plain JUnit test cannot
 * construct `WalletService` (Android `Context`), so this asserts on the pure
 * `WalletScanSet.scanAddresses(...)` output the UTXO loop consumes.
 */
class WalletUtxoScanTest {

    private val derive: (BitcoinAddressType, Int, Boolean) -> String =
        { type, index, internal -> "${type.name.lowercase()}-${if (internal) "1" else "0"}-$index" }

    @Test
    fun `utxo source includes the internal chain reserved and index 0`() {
        val scanned = scanAddresses(
            HdPointers(nextExternal = 2, nextChange = 3, reserved = setOf(9)),
            derive
        )
        assertTrue(scanned.any { it.internal && it.index == 0 && it.type == BitcoinAddressType.SEGWIT })
        assertTrue(scanned.any { it.index == 9 && !it.internal })
        assertTrue(scanned.any { it.index == 0 && it.type == BitcoinAddressType.LEGACY && !it.internal })
        // The change window must be inside the UTXO source, not only the balance source.
        assertTrue(scanned.any { it.internal && it.index == 22 })
    }

    @Test
    fun `a utxo sitting on an internal change index is selectable`() {
        val scanned = scanAddresses(HdPointers(nextChange = 3), derive)
        val internal = scanned.first { it.internal && it.index == 2 }
        val coins = listOf(
            SelectedCoin(
                internal.type, internal.index, internal.internal,
                ChainMonitor.Utxo("change-tx", 0, 100_000L)
            )
        )
        val spend = CoinSelector.select(coins, 50_000L, feeRate = 10L)
        assertEquals(1, spend.chosen.size)
        assertTrue(spend.chosen.single().internal)
    }
}
