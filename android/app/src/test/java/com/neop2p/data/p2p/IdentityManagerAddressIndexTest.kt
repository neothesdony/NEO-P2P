package com.neop2p.data.p2p

import com.neop2p.domain.model.BitcoinAddressType
import org.bitcoinj.base.Address
import org.bitcoinj.params.TestNet3Params
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * P0.1 — indexed address derivation.
 *
 * `IdentityManager` itself needs an Android `Context`, so this exercises the
 * pure building blocks it delegates to ([SeedCache]) and proves the invariant
 * that matters: index 0 external is bit-identical to the legacy single-address
 * wallet (`PATH_BITCOIN`), so no existing funds or escrow role address moves.
 */
class IdentityManagerAddressIndexTest {

    private val params = TestNet3Params.get()

    private val mnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    private fun stretch(words: List<String>): ByteArray {
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = javax.crypto.spec.PBEKeySpec(
            words.joinToString(" ").toCharArray(),
            "mnemonic".toByteArray(Charsets.UTF_8),
            2048,
            512
        )
        return factory.generateSecret(spec).encoded
    }

    private fun cache() = SeedCache { stretch(it) }

    @Test
    fun `index 0 external is the legacy PATH_BITCOIN key`() {
        val legacy = KeyDerivation.deriveSecp256k1(stretch(mnemonic), IdentityManager.PATH_BITCOIN)
        val indexed = cache().bitcoinKey(mnemonic, 0, internal = false)
        assertArrayEquals(legacy, indexed)
        assertEquals("m/44'/0'/0'/0/0", SeedCache.bitcoinPath(0, internal = false))
    }

    @Test
    fun `index 0 external address equals the legacy address for both types`() {
        val legacyPriv = KeyDerivation.deriveSecp256k1(stretch(mnemonic), IdentityManager.PATH_BITCOIN)
        val indexedPriv = cache().bitcoinKey(mnemonic, 0, internal = false)
        for (type in BitcoinAddressType.entries) {
            assertEquals(
                SeedCache.addressFor(type, legacyPriv, params),
                SeedCache.addressFor(type, indexedPriv, params)
            )
        }
    }

    @Test
    fun `external internal and index all produce different addresses`() {
        val c = cache()
        val ext0 = SeedCache.addressFor(
            BitcoinAddressType.SEGWIT, c.bitcoinKey(mnemonic, 0, internal = false), params
        )
        val ext1 = SeedCache.addressFor(
            BitcoinAddressType.SEGWIT, c.bitcoinKey(mnemonic, 1, internal = false), params
        )
        val int0 = SeedCache.addressFor(
            BitcoinAddressType.SEGWIT, c.bitcoinKey(mnemonic, 0, internal = true), params
        )
        assertNotEquals(ext0, ext1)
        assertNotEquals(ext0, int0)
    }

    @Test
    fun `both types parse under the active params`() {
        val c = cache()
        for (type in BitcoinAddressType.entries) {
            val addr = SeedCache.addressFor(type, c.bitcoinKey(mnemonic, 0, internal = false), params)
            assertFalse(addr.isBlank())
            assertEquals(addr, Address.fromString(params, addr).toString())
        }
    }
}
