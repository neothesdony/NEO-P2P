package com.neop2p.admind

import com.neop2p.NeoP2PConfig
import com.neop2p.data.p2p.Bip39
import com.neop2p.data.p2p.IdentityBlob
import com.neop2p.data.p2p.IdentityDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The positive case (`isArbitrator == true`) is intentionally untestable in-repo: the
 * admin mnemonic is deliberately absent from the source tree (NeoP2PConfig.kt:108).
 * It is covered manually by the operator via `admind init`/`whoami`; every rejection
 * path is covered here.
 */
class ArbitratorUnlockTest {

    private val abandonMnemonic = List(11) { "abandon" } + "about"

    private fun blob(words: List<String> = abandonMnemonic) = IdentityBlob(
        seedPhrase = words,
        peerId = "unused",
        nostrPubkeyHex = "",
        nostrPrivateKeyHex = "",
        nickname = "Arbitrator",
        lnNodeId = ""
    )

    @Test
    fun `arbitrator pubkey derives exactly like the app`() {
        val seed = Bip39.mnemonicToSeed(abandonMnemonic)
        assertEquals(IdentityDerivation.arbitratorPubKeyHex(seed), ArbitratorUnlock.arbitratorPubKeyHex(blob()))
    }

    @Test
    fun `derivation is deterministic`() {
        assertEquals(ArbitratorUnlock.arbitratorPubKeyHex(blob()), ArbitratorUnlock.arbitratorPubKeyHex(blob()))
    }

    @Test
    fun `derive matches the shared IdentityDerivation result`() {
        val seed = Bip39.mnemonicToSeed(abandonMnemonic)
        val expected = IdentityDerivation.derive(seed)
        val actual = ArbitratorUnlock.derive(blob())
        assertEquals(expected.peerId, actual.peerId)
        assertEquals(expected.nostrPubkeyHex, actual.nostrPubkeyHex)
        assertEquals(expected.nostrPrivateKeyHex, actual.nostrPrivateKeyHex)
        assertTrue(actual.peerId.isNotEmpty())
    }

    @Test
    fun `a non-arbitrator mnemonic is rejected without leaking the secret`() {
        assertFalse(ArbitratorUnlock.isArbitrator(blob()))
        try {
            ArbitratorUnlock.requireArbitrator(blob())
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            val message = e.message!!
            assertFalse(message.contains("abandon"))
            assertFalse(message.contains(ArbitratorUnlock.arbitratorPubKeyHex(blob())))
            assertTrue(message.contains(NeoP2PConfig.ARBITRATOR_PUBKEY.take(12)))
        }
    }

    @Test
    fun `an invalid checksum is rejected before derivation`() {
        try {
            ArbitratorUnlock.arbitratorPubKeyHex(blob(List(11) { "abandon" }))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("checksum"))
        }
    }

    @Test
    fun `the shipped arbitrator signature verifies`() {
        ArbitratorUnlock.requireIntegrity()
    }
}
