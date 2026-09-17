package com.neop2p.data.wallet

import com.neop2p.domain.model.BitcoinAddressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletScanSetTest {

    /** Unique fake address per (type, chain, index). */
    private val derive: (BitcoinAddressType, Int, Boolean) -> String =
        { type, index, internal -> "${type.name.lowercase()}-${if (internal) "1" else "0"}-$index" }

    @Test
    fun `empty pointers yield exactly 40 external plus 20 internal`() {
        val set = scanAddresses(HdPointers(), derive)
        assertEquals(60, set.size)
        assertEquals(40, set.count { !it.internal })
        assertEquals(20, set.count { it.internal })
        assertTrue(set.filter { it.internal }.all { it.type == BitcoinAddressType.SEGWIT })
    }

    @Test
    fun `index 0 external is present for both types in every scan set`() {
        for (pointers in listOf(
            HdPointers(),
            HdPointers(nextExternal = 5, nextChange = 3),
            HdPointers(nextExternal = 0, nextChange = 0, reserved = setOf(0, 2))
        )) {
            val set = scanAddresses(pointers, derive)
            for (type in BitcoinAddressType.entries) {
                assertTrue(
                    "index 0 external $type missing",
                    set.any { it.type == type && it.index == 0 && !it.internal }
                )
            }
        }
    }

    @Test
    fun `internal change chain is included`() {
        val set = scanAddresses(HdPointers(nextChange = 4), derive)
        assertTrue(set.any { it.internal && it.index == 0 && it.type == BitcoinAddressType.SEGWIT })
        assertTrue(set.any { it.internal && it.index == 23 && it.type == BitcoinAddressType.SEGWIT })
    }

    @Test
    fun `reserved indices are included`() {
        val set = scanAddresses(HdPointers(reserved = setOf(21, 22)), derive)
        assertTrue(set.any { it.index == 21 && !it.internal })
        assertTrue(set.any { it.index == 22 && !it.internal })
    }

    @Test
    fun `no duplicate addresses`() {
        val set = scanAddresses(
            HdPointers(nextExternal = 3, nextChange = 2, reserved = setOf(0, 3)),
            derive
        )
        assertEquals(set.size, set.map { it.address }.distinct().size)
    }

    @Test
    fun `window slides with the pointer`() {
        val set = scanAddresses(HdPointers(nextExternal = 10), derive)
        // 0..29 external for both types.
        assertTrue(set.any { it.index == 29 && !it.internal })
        assertFalse(set.any { it.index == 30 && !it.internal })
    }
}
