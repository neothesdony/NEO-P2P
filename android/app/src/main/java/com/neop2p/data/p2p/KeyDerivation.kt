package com.neop2p.data.p2p

import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * BIP-32 (secp256k1) and SLIP-10 (ed25519, curve25519) hierarchical key derivation.
 *
 * Pure Kotlin + Bouncy Castle, no JNI — works in JVM unit tests and on device.
 * Verified against official BIP-32 and SLIP-10 test vectors.
 */
object KeyDerivation {

    private val SECP256K1_N = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16
    )

    private val secp256k1Spec by lazy {
        org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256k1")
    }

    /**
     * BIP-32 CKDpriv for secp256k1. Child key = (IL + kpar) mod n.
     */
    fun deriveSecp256k1(seed: ByteArray, path: String): ByteArray {
        val (key, chainCode) = masterKey(seed, "Bitcoin seed")
        return derivePath(key, chainCode, path) { parentKey, parentChainCode, index, hardened ->
            val data = if (hardened) {
                byteArrayOf(0x00) + parentKey + ser32(index)
            } else {
                compressedPubKey(parentKey) + ser32(index)
            }
            val i = hmacSha512(parentChainCode, data)
            val il = BigInteger(1, i.copyOfRange(0, 32))
            val childKey = il.add(BigInteger(1, parentKey)).mod(SECP256K1_N)
            childKey.toByteArray().let { bytes ->
                if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size)
                else ByteArray(32 - bytes.size) + bytes
            } to i.copyOfRange(32, 64)
        }
    }

    /**
     * SLIP-10 for ed25519. Hardened-only derivation, child key = IL.
     */
    fun deriveEd25519(seed: ByteArray, path: String): ByteArray {
        val (key, chainCode) = masterKey(seed, "ed25519 seed")
        return derivePath(key, chainCode, path) { parentKey, parentChainCode, index, _ ->
            val data = byteArrayOf(0x00) + parentKey + ser32(index)
            val i = hmacSha512(parentChainCode, data)
            i.copyOfRange(0, 32) to i.copyOfRange(32, 64)
        }
    }

    /**
     * SLIP-10 for curve25519 (X25519). Hardened-only derivation, child key = IL.
     */
    fun deriveCurve25519(seed: ByteArray, path: String): ByteArray {
        val (key, chainCode) = masterKey(seed, "curve25519 seed")
        return derivePath(key, chainCode, path) { parentKey, parentChainCode, index, _ ->
            val data = byteArrayOf(0x00) + parentKey + ser32(index)
            val i = hmacSha512(parentChainCode, data)
            i.copyOfRange(0, 32) to i.copyOfRange(32, 64)
        }
    }

    /**
     * Derive the libp2p PeerID (base58) from an Ed25519 key at the given SLIP-10 path.
     * Matches io.libp2p.core.PeerId.fromPubKey exactly.
     */
    fun deriveLibp2pPeerId(seed: ByteArray, path: String): String =
        deriveLibp2pPeerIdFromKey(deriveEd25519(seed, path))

    /**
     * Derive the libp2p PeerID (base58) from a raw 32-byte Ed25519 private key.
     */
    fun deriveLibp2pPeerIdFromKey(privateKey: ByteArray): String {
        val privKey = io.libp2p.crypto.keys.unmarshalEd25519PrivateKey(privateKey)
        return io.libp2p.core.PeerId.fromPubKey(privKey.publicKey()).toBase58()
    }

    /**
     * x-only 32-byte secp256k1 public key (BIP-340 / Nostr NIP-01).
     */
    fun secp256k1XOnlyPubKey(privateKey: ByteArray): ByteArray {
        val encoded = compressedPubKey(privateKey)
        return encoded.copyOfRange(1, 33)
    }

    private fun masterKey(seed: ByteArray, curve: String): Pair<ByteArray, ByteArray> {
        val i = hmacSha512(curve.toByteArray(), seed)
        return i.copyOfRange(0, 32) to i.copyOfRange(32, 64)
    }

    private fun derivePath(
        masterKey: ByteArray,
        masterChainCode: ByteArray,
        path: String,
        ckd: (ByteArray, ByteArray, Int, Boolean) -> Pair<ByteArray, ByteArray>
    ): ByteArray {
        var key = masterKey
        var chainCode = masterChainCode
        val segments = path.removePrefix("m/").split("/")
        for (segment in segments) {
            val hardened = segment.endsWith("'")
            val index = segment.removeSuffix("'").toInt()
            val childIndex = if (hardened) index or (1 shl 31) else index
            val (childKey, childChainCode) = ckd(key, chainCode, childIndex, hardened)
            key = childKey
            chainCode = childChainCode
        }
        return key
    }

    private fun compressedPubKey(privateKey: ByteArray): ByteArray {
        val point = secp256k1Spec.g.multiply(BigInteger(1, privateKey))
        return point.getEncoded(true)
    }

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    private fun ser32(i: Int): ByteArray = byteArrayOf(
        ((i shr 24) and 0xFF).toByte(),
        ((i shr 16) and 0xFF).toByte(),
        ((i shr 8) and 0xFF).toByte(),
        (i and 0xFF).toByte()
    )
}
