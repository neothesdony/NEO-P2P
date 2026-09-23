package com.neop2p.data.p2p.ratchet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RatchetKdfTest {

    private val ikm = ByteArray(32) { it.toByte() }
    private val salt = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `hkdf is deterministic`() {
        assertArrayEquals(
            RatchetKdf.hkdf(salt, ikm, "info", 32),
            RatchetKdf.hkdf(salt, ikm, "info", 32)
        )
    }

    @Test
    fun `hkdf output length is honored`() {
        assertEquals(64, RatchetKdf.hkdf(salt, ikm, "info", 64).size)
        assertEquals(32, RatchetKdf.hkdf(salt, ikm, "info", 32).size)
    }

    @Test
    fun `hkdf domain separation changes the output`() {
        val a = RatchetKdf.hkdf(salt, ikm, RatchetKdf.INFO_ROOT, 32)
        val b = RatchetKdf.hkdf(salt, ikm, RatchetKdf.INFO_CHAIN, 32)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `rootKeyStep splits into distinct root and chain halves`() {
        val (root, chain) = RatchetKdf.rootKeyStep(ikm, salt)
        assertEquals(32, root.size)
        assertEquals(32, chain.size)
        assertFalse(root.contentEquals(chain))
    }

    @Test
    fun `chainKeyStep produces distinct message and next chain keys`() {
        val (mk, next) = RatchetKdf.chainKeyStep(ikm)
        assertEquals(32, mk.size)
        assertEquals(32, next.size)
        assertFalse(mk.contentEquals(next))
        assertFalse(next.contentEquals(ikm))
    }

    @Test
    fun `x3dhSecret is deterministic and order-sensitive`() {
        val dh1 = ByteArray(32) { 1 }
        val dh2 = ByteArray(32) { 2 }
        val dh3 = ByteArray(32) { 3 }
        assertArrayEquals(RatchetKdf.x3dhSecret(dh1, dh2, dh3), RatchetKdf.x3dhSecret(dh1, dh2, dh3))
        assertFalse(RatchetKdf.x3dhSecret(dh1, dh2, dh3).contentEquals(RatchetKdf.x3dhSecret(dh2, dh1, dh3)))
    }
}
