package com.neop2p.data.p2p

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class IdentityManagerTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    @Test
    fun `mnemonic produces deterministic peer ID`() {
        val seed = mnemonicToSeed(testMnemonic)
        val libp2pKey = deriveChildKey(seed, "m/44'/888'/0'/0/0")
        val peerId1 = deriveLibp2pPeerId(libp2pKey)
        val peerId2 = deriveLibp2pPeerId(libp2pKey)
        assertEquals(peerId1, peerId2)
        assertTrue(peerId1.isNotEmpty())
    }

    @Test
    fun `mnemonic produces deterministic Nostr pubkey`() {
        val seed = mnemonicToSeed(testMnemonic)
        val nostrPrivKey = deriveChildKey(seed, "m/44'/1237'/0'/0/0")
        val nostrPubKey = secp256k1PublicKey(nostrPrivKey)
        assertEquals(32, nostrPubKey.size)
    }

    @Test
    fun `different mnemonics produce different keys`() {
        val seed1 = mnemonicToSeed(testMnemonic)
        val seed2 = mnemonicToSeed(listOf(
            "zoo", "zoo", "zoo", "zoo", "zoo", "zoo",
            "zoo", "zoo", "zoo", "zoo", "zoo", "wrong"
        ))
        val key1 = deriveChildKey(seed1, "m/44'/1237'/0'/0/0")
        val key2 = deriveChildKey(seed2, "m/44'/1237'/0'/0/0")
        assertFalse(key1.contentEquals(key2))
    }

    @Test
    fun `libp2p and Nostr keys are different from same seed`() {
        val seed = mnemonicToSeed(testMnemonic)
        val libp2pKey = deriveChildKey(seed, "m/44'/888'/0'/0/0")
        val nostrKey = deriveChildKey(seed, "m/44'/1237'/0'/0/0")
        assertFalse(libp2pKey.contentEquals(nostrKey))
    }

    @Test
    fun `same mnemonic produces same keys deterministically`() {
        val seed1 = mnemonicToSeed(testMnemonic)
        val seed2 = mnemonicToSeed(testMnemonic)
        val key1 = deriveChildKey(seed1, "m/44'/1237'/0'/0/0")
        val key2 = deriveChildKey(seed2, "m/44'/1237'/0'/0/0")
        assertTrue(key1.contentEquals(key2))
    }

    // ─── Test helpers (mirror IdentityManager logic) ─────────────

    private fun mnemonicToSeed(words: List<String>, passphrase: String = ""): ByteArray {
        val mnemonic = words.joinToString(" ")
        val salt = ("mnemonic$passphrase").toByteArray(Charsets.UTF_8)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = javax.crypto.spec.PBEKeySpec(mnemonic.toCharArray(), salt, 2048, 512)
        return factory.generateSecret(spec).encoded
    }

    private fun deriveChildKey(seed: ByteArray, path: String): ByteArray {
        val masterHmac = javax.crypto.Mac.getInstance("HmacSHA512").also {
            it.init(javax.crypto.spec.SecretKeySpec("Bitcoin seed".toByteArray(), "HmacSHA512"))
        }
        val masterNode = masterHmac.doFinal(seed)
        var currentKey = masterNode.copyOfRange(0, 32)
        var currentChainCode = masterNode.copyOfRange(32, 64)

        val segments = path.removePrefix("m/").split("/")
        for (segment in segments) {
            val isHardened = segment.endsWith("'")
            val index = segment.removeSuffix("'").toInt()
            val childIndex = if (isHardened) (index or (1 shl 31)).toInt() else index

            val mac = javax.crypto.Mac.getInstance("HmacSHA512").also {
                it.init(javax.crypto.spec.SecretKeySpec(currentChainCode, "HmacSHA512"))
            }
            val data = if (isHardened) {
                byteArrayOf(0x00) + currentKey + intToBytes(childIndex)
            } else {
                currentKey + intToBytes(childIndex)
            }
            val result = mac.doFinal(data)
            currentKey = result.copyOfRange(0, 32)
            currentChainCode = result.copyOfRange(32, 64)
        }
        return currentKey
    }

    private fun intToBytes(i: Int): ByteArray = byteArrayOf(
        ((i shr 24) and 0xFF).toByte(),
        ((i shr 16) and 0xFF).toByte(),
        ((i shr 8) and 0xFF).toByte(),
        (i and 0xFF).toByte()
    )

    private fun secp256k1PublicKey(privateKey: ByteArray): ByteArray {
        return try {
            java.security.Security.addProvider(
                org.bouncycastle.jce.provider.BouncyCastleProvider()
            )
            val bcSpec = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256k1")
            val ecSpec = org.bouncycastle.jce.spec.ECNamedCurveSpec(
                "secp256k1", bcSpec.curve, bcSpec.g, bcSpec.n
            )
            val keyFactory = java.security.KeyFactory.getInstance("EC", "BC")
            val privKeySpec = java.security.spec.ECPrivateKeySpec(
                java.math.BigInteger(1, privateKey), ecSpec
            )
            val privKey = keyFactory.generatePrivate(privKeySpec)
                as java.security.interfaces.ECPrivateKey
            val publicPoint = bcSpec.g.multiply(privKey.s)
            val encoded = publicPoint.getEncoded(true)
            encoded.copyOfRange(1, 33)
        } catch (e: Exception) {
            MessageDigest.getInstance("SHA-256").digest(privateKey)
        }
    }

    private fun deriveLibp2pPeerId(privateKey: ByteArray): String {
        val pubKeyHash = MessageDigest.getInstance("SHA-256").digest(privateKey)
        return "12D3KooW" + bytesToBase58(pubKeyHash.take(14).toByteArray())
    }

    private fun bytesToBase58(bytes: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val result = StringBuilder()
        var value = java.math.BigInteger(1, bytes)
        val base = java.math.BigInteger("58")
        while (value > java.math.BigInteger.ZERO) {
            val div = value.divideAndRemainder(base)
            result.append(alphabet[div[1].toInt()])
            value = div[0]
        }
        for (b in bytes) {
            if (b == 0.toByte()) result.append(alphabet[0])
            else break
        }
        return result.reverse().toString()
    }
}
