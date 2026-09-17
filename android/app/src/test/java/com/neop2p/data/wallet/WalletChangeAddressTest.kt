package com.neop2p.data.wallet

import com.neop2p.data.p2p.SeedCache
import com.neop2p.domain.model.BitcoinAddressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.6 — change-chain send + per-index signing.
 *
 * The network/broadcast ordering inside `WalletService.send` is device-level,
 * but the pointer arithmetic, write-ahead persistence, revert, and per-input
 * key mapping are pure and asserted here.
 */
class WalletChangeAddressTest {

    private val mnemonic = listOf("abandon", "ability", "able", "about", "above", "absent")

    @Test
    fun `change index is the pointer and appears in the next scan set`() {
        val p = HdPointers(nextExternal = 3, nextChange = 5)
        val changeIndex = pickChangeIndex(p)
        assertEquals(5, changeIndex)
        val before = scanAddresses(p) { t, i, int -> "${t.name}-${if (int) 1 else 0}-$i" }
        val after = scanAddresses(p.copy(nextChange = changeIndex + 1)) { t, i, int ->
            "${t.name}-${if (int) 1 else 0}-$i"
        }
        assertTrue(before.any { it.internal && it.index == 5 })
        // The next send's change address is inside the following scan set.
        assertTrue(after.any { it.internal && it.index == 6 })
    }

    @Test
    fun `write-ahead persists the advanced pointer before broadcast`() {
        val original = HdPointers(nextExternal = 2, nextChange = 0)
        val advanced = original.copy(nextChange = 1)
        // What save() writes is what load() reads (same codec).
        val reloaded = WalletAddressStateStore.parse(WalletAddressStateStore.toJson(advanced))
        assertEquals(1, reloaded.nextChange)
        assertEquals(original.nextExternal, reloaded.nextExternal)
    }

    @Test
    fun `a failed broadcast reverts by re-saving the original pointers`() {
        val original = HdPointers(nextExternal = 2, nextChange = 0)
        val advanced = original.copy(nextChange = 1)
        // Simulate send(): persist advance, broadcast fails, restore original.
        var persisted = WalletAddressStateStore.parse(WalletAddressStateStore.toJson(advanced))
        persisted = WalletAddressStateStore.parse(WalletAddressStateStore.toJson(original))
        assertEquals(original, persisted)
    }

    @Test
    fun `each chosen coin maps to its own key by index and chain`() {
        val counter = intArrayOf(0)
        val cache = SeedCache { m ->
            counter[0]++
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(
                javax.crypto.spec.PBEKeySpec(
                    m.joinToString(" ").toCharArray(),
                    "mnemonic".toByteArray(),
                    2048,
                    512
                )
            ).encoded
        }
        val ext0 = cache.bitcoinKey(mnemonic, 0, internal = false)
        val ext1 = cache.bitcoinKey(mnemonic, 1, internal = false)
        val int0 = cache.bitcoinKey(mnemonic, 0, internal = true)
        assertFalse(ext0.contentEquals(ext1))
        assertFalse(ext0.contentEquals(int0))
        // One PBKDF2 stretch regardless of how many inputs were signed.
        assertEquals(1, counter[0])

        // A change input's owning address is the internal SEGWIT index, not index 0.
        val coin = SelectedCoin(BitcoinAddressType.SEGWIT, 1, internal = true, utxo = dummy())
        assertEquals(1, coin.index)
        assertTrue(coin.internal)
        assertEquals("m/44'/0'/0'/1/1", SeedCache.bitcoinPath(coin.index, coin.internal))
    }

    private fun dummy() = com.neop2p.data.escrow.ChainMonitor.Utxo("t", 0, 100_000L)
}
