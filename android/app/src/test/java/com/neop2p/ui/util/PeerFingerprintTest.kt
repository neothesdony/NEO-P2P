package com.neop2p.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerFingerprintTest {

    // Synthetic 2048-entry wordlist covering every possible 11-bit index —
    // proves index mapping without shipping the canonical list in the test.
    private val wordList: List<String> = (0 until 2048).map { "w$it" }

    @Test
    fun `fingerprint is deterministic`() {
        val a = PeerFingerprint.words("12D3KooWpeerA", wordList)
        val b = PeerFingerprint.words("12D3KooWpeerA", wordList)
        assertEquals(a, b)
    }

    @Test
    fun `fingerprint changes with peer id`() {
        val a = PeerFingerprint.words("12D3KooWpeerA", wordList)
        val b = PeerFingerprint.words("12D3KooWpeerB", wordList)
        assertNotEquals(a, b)
    }

    @Test
    fun `fingerprint yields eight words from the list`() {
        val words = PeerFingerprint.words("12D3KooWpeerA", wordList)
        assertEquals(8, words.size)
        assertTrue(words.all { it in wordList })
    }

    @Test
    fun `display joins with spaces`() {
        val display = PeerFingerprint.display("12D3KooWpeerA", wordList)
        assertEquals(8, display.split(" ").size)
    }

    @Test
    fun `empty wordlist falls back gracefully`() {
        val words = PeerFingerprint.words("peer", emptyList())
        assertEquals(8, words.size)
        assertTrue(words.all { it.startsWith("word") })
    }
}
