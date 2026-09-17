package com.neop2p.data.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdAddressBookTest {

    @Test
    fun `empty pointers scan the first gap window`() {
        assertEquals((0 until 20).toList(), scanSet(0))
        assertEquals(20, scanSet(0).size)
    }

    @Test
    fun `scanSet upper bound is exclusive`() {
        // BlueWallet: c < next + gap_limit. next=1 -> 0..20 (21 indices).
        assertEquals(21, scanSet(1).size)
        assertEquals(20, scanSet(1).last())
    }

    @Test
    fun `advance moves past the highest used index`() {
        val p = advance(HdPointers(), usedExternal = setOf(0, 3, 2), usedChange = emptySet())
        assertEquals(4, p.nextExternal)
        assertEquals(0, p.nextChange)
    }

    @Test
    fun `advance never moves backwards`() {
        val start = HdPointers(nextExternal = 10, nextChange = 5)
        val p = advance(start, usedExternal = setOf(2), usedChange = setOf(1))
        assertEquals(10, p.nextExternal)
        assertEquals(5, p.nextChange)
    }

    @Test
    fun `advance with no usage is a no-op`() {
        val start = HdPointers(nextExternal = 4, nextChange = 2)
        assertEquals(start, advance(start, emptySet(), emptySet()))
    }

    @Test
    fun `pickChangeIndex equals the pointer and does not advance until marked used`() {
        val p = HdPointers(nextChange = 7)
        assertEquals(7, pickChangeIndex(p))
        assertEquals(7, pickChangeIndex(p))
        assertEquals(8, advance(p, emptySet(), setOf(7)).nextChange)
    }

    @Test
    fun `pickReceiveIndex skips reserved and never goes below nextExternal`() {
        val p = HdPointers(nextExternal = 2).let { reserve(reserve(it, 2), 4) }
        assertEquals(3, pickReceiveIndex(p))
        // An index reserved below the pointer is irrelevant.
        val q = reserve(HdPointers(nextExternal = 5), 0)
        assertEquals(5, pickReceiveIndex(q))
    }

    @Test
    fun `reserve and release round-trip`() {
        val p = reserve(HdPointers(), 0)
        assertEquals(setOf(0), p.reserved)
        assertEquals(p, reserve(p, 0))
        val q = release(p, 0)
        assertEquals(emptySet<Int>(), q.reserved)
        assertEquals(q, release(q, 0))
    }

    @Test
    fun `reserved index is skipped for receive but still scannable`() {
        val p = reserve(HdPointers(), 0)
        assertEquals(1, pickReceiveIndex(p))
        assertTrue(scanSet(p.nextExternal).contains(0))
        assertFalse(scanSet(p.nextExternal).contains(20))
    }
}
