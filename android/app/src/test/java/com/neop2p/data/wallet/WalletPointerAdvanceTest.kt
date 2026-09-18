package com.neop2p.data.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.8 — external used-address discovery. The pure decision is
 * `usedIndices(...)` + `advance(...)`; `loadState` only wires them to real
 * activity.
 */
class WalletPointerAdvanceTest {

    @Test
    fun `activity at index 3 advances past 3 and keeps the gap`() {
        val window = scanSet(0)
        val used = usedIndices(setOf(3), window)
        assertEquals(setOf(3), used)
        val p = advance(HdPointers(), used, emptySet())
        assertEquals(4, p.nextExternal)
        // The new window still includes the used index and keeps the +gap lookahead.
        val nextWindow = scanSet(p.nextExternal)
        assertTrue(nextWindow.contains(3))
        assertEquals(GAP_LIMIT, nextWindow.size - p.nextExternal)
        assertEquals(4 + GAP_LIMIT, nextWindow.size)
    }

    @Test
    fun `no activity leaves the pointer unchanged`() {
        val p = HdPointers(nextExternal = 5)
        assertEquals(p, advance(p, usedIndices(emptySet(), scanSet(5)), emptySet()))
    }

    @Test
    fun `activity outside the window is ignored`() {
        val used = usedIndices(setOf(50), scanSet(0))
        assertEquals(emptySet<Int>(), used)
        val p = HdPointers()
        assertEquals(p, advance(p, used, emptySet()))
    }

    @Test
    fun `partial failure is a no-op`() {
        // loadState skips advanceExternalPointer entirely when stale; the pure
        // equivalent is an empty activity set.
        val p = HdPointers()
        val used = usedIndices(emptySet(), scanSet(0))
        assertEquals(p, advance(p, used, emptySet()))
    }

    @Test
    fun `advance never touches the change pointer`() {
        val p = HdPointers(nextExternal = 1, nextChange = 9)
        val advanced = advance(p, usedExternal = setOf(1), usedChange = emptySet())
        assertEquals(2, advanced.nextExternal)
        assertEquals(9, advanced.nextChange)
    }

    @Test
    fun `multi-index activity advances to the highest plus one`() {
        val used = usedIndices(setOf(2, 7, 4), scanSet(0))
        assertEquals(setOf(2, 4, 7), used)
        assertEquals(8, advance(HdPointers(), used, emptySet()).nextExternal)
    }
}
