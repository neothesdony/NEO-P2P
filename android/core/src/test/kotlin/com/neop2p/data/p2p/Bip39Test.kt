package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip39Test {

    private val abandonAbout = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `wordlist has 2048 distinct entries`() {
        val words = Bip39.wordlist()
        assertEquals(2048, words.size)
        assertEquals(2048, words.toSet().size)
        assertEquals("abandon", words.first())
        assertEquals("zoo", words.last())
    }

    @Test
    fun `known mnemonic stretches to the published BIP-39 seed`() {
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            hex(Bip39.mnemonicToSeed(abandonAbout))
        )
    }

    @Test
    fun `known mnemonic with TREZOR passphrase stretches to the published seed`() {
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            hex(Bip39.mnemonicToSeed(abandonAbout, "TREZOR"))
        )
    }

    @Test
    fun `checksum validation accepts a valid mnemonic and rejects bad input`() {
        assertTrue(Bip39.validateChecksum(abandonAbout))
        assertFalse(Bip39.validateChecksum(abandonAbout.dropLast(1) + "wrong"))
        assertFalse(Bip39.validateChecksum(abandonAbout.dropLast(1)))
        assertFalse(Bip39.validateChecksum(emptyList()))
    }

    @Test
    fun `generated mnemonics are 12 valid words that pass the checksum`() {
        val wordlist = Bip39.wordlist()
        repeat(50) {
            val words = Bip39.generateMnemonic()
            assertEquals(12, words.size)
            assertTrue(words.all { it in wordlist })
            assertTrue("generated mnemonic failed checksum: $words", Bip39.validateChecksum(words))
        }
    }
}
