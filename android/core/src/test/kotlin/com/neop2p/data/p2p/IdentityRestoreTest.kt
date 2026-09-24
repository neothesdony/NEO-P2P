package com.neop2p.data.p2p

import com.neop2p.NeoP2PConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityRestoreTest {

    @Test
    fun `stored nickname is returned`() {
        assertEquals("Trader One", IdentityRestore.nickname("Trader One"))
    }

    @Test
    fun `null or blank falls back to Anonymous`() {
        assertEquals("Anonymous", IdentityRestore.nickname(null))
        assertEquals("Anonymous", IdentityRestore.nickname(""))
        assertEquals("Anonymous", IdentityRestore.nickname("   "))
    }

    @Test
    fun `control characters are stripped`() {
        assertEquals("evil", IdentityRestore.nickname("ev\u0000il\n"))
    }

    @Test
    fun `overlong nickname is capped`() {
        val long = "x".repeat(100)
        assertEquals(NeoP2PConfig.MAX_NICKNAME_LENGTH, IdentityRestore.nickname(long).length)
    }

    @Test
    fun `unicode nickname survives`() {
        assertEquals("Résumé 🧑", IdentityRestore.nickname("Résumé 🧑"))
    }
}
