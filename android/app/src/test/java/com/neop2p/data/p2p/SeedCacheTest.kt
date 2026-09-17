package com.neop2p.data.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedCacheTest {

    private val mnemonicA = listOf("legal", "winner", "thank", "year", "wave", "sausage")
    private val mnemonicB = listOf("abandon", "ability", "able", "about", "above", "absent")

    /** Deterministic fake stretch; the counter proves how often it ran. */
    private fun cache(counter: IntArray) = SeedCache { m ->
        counter[0]++
        ("seed:" + m.joinToString("-")).toByteArray().copyOf(64)
    }

    @Test
    fun `one stretch across many indices for the same identity`() {
        val counter = intArrayOf(0)
        val cache = cache(counter)
        (0 until 25).forEach { cache.bitcoinKey(mnemonicA, it, internal = false) }
        (0 until 5).forEach { cache.bitcoinKey(mnemonicA, it, internal = true) }
        assertEquals(1, counter[0])
        assertEquals(1, cache.stretchCount)
    }

    @Test
    fun `invalidation forces a re-stretch`() {
        val counter = intArrayOf(0)
        val cache = cache(counter)
        cache.bitcoinKey(mnemonicA, 0, internal = false)
        cache.invalidate()
        cache.bitcoinKey(mnemonicA, 0, internal = false)
        assertEquals(2, counter[0])
        assertEquals(2, cache.stretchCount)
    }

    @Test
    fun `repeated key request is memoized and stable`() {
        val counter = intArrayOf(0)
        val cache = cache(counter)
        assertArrayEquals(
            cache.bitcoinKey(mnemonicA, 3, internal = false),
            cache.bitcoinKey(mnemonicA, 3, internal = false)
        )
        assertEquals(1, cache.stretchCount)
    }

    @Test
    fun `returned key is a copy the caller can wipe`() {
        val cache = cache(intArrayOf(0))
        val first = cache.bitcoinKey(mnemonicA, 3, internal = false)
        first.fill(0)
        val second = cache.bitcoinKey(mnemonicA, 3, internal = false)
        assertFalse("cache must not hand out its internal buffer", second.all { it == 0.toByte() })
    }

    @Test
    fun `restored mnemonic yields a different key`() {
        val cache = cache(intArrayOf(0))
        val before = cache.bitcoinKey(mnemonicA, 0, internal = false)
        val after = cache.bitcoinKey(mnemonicB, 0, internal = false)
        assertFalse(before.contentEquals(after))
    }

    @Test
    fun `external internal and indices all differ`() {
        val cache = cache(intArrayOf(0))
        val ext0 = cache.bitcoinKey(mnemonicA, 0, internal = false)
        val ext1 = cache.bitcoinKey(mnemonicA, 1, internal = false)
        val int0 = cache.bitcoinKey(mnemonicA, 0, internal = true)
        assertFalse(ext0.contentEquals(ext1))
        assertFalse(ext0.contentEquals(int0))
        assertEquals(32, ext0.size)
    }

    @Test
    fun `index zero external path is the legacy wallet path`() {
        assertEquals(IdentityManager.PATH_BITCOIN, SeedCache.bitcoinPath(0, internal = false))
        assertEquals("m/44'/0'/0'/1/0", SeedCache.bitcoinPath(0, internal = true))
        assertTrue(cache(intArrayOf(0)).bitcoinKey(mnemonicA, 0, internal = false).isNotEmpty())
    }
}
