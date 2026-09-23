package com.neop2p.data.p2p.ratchet

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869) and the Double Ratchet's domain-separated KDFs
 * (E2EE v2, 2026-09-23). Pure JVM so the ratchet is testable without Android.
 *
 *   X3DH:  SK            = HKDF(salt=zeros32, ikm = DH1||DH2||DH3, "x3dh", 32)
 *   Root:  (root, chain) = HKDF(salt=rootKey,  ikm = dhOut,        "root", 64)
 *   Chain: (mk, nextCk)  = HKDF(salt=zeros32,  ikm = chainKey,     "chain", 64)
 */
object RatchetKdf {
    private const val HASH_LEN = 32
    const val INFO_ROOT = "neop2p-ratchet-root-v2"
    const val INFO_CHAIN = "neop2p-ratchet-chain-v2"
    const val INFO_X3DH = "neop2p-ratchet-x3dh-v2"

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LEN)) { "HKDF length out of range: $length" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun hkdf(salt: ByteArray, ikm: ByteArray, info: String, length: Int): ByteArray =
        expand(extract(salt, ikm), info.toByteArray(Charsets.UTF_8), length)

    fun x3dhSecret(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray): ByteArray =
        hkdf(ByteArray(HASH_LEN), dh1 + dh2 + dh3, INFO_X3DH, HASH_LEN)

    fun rootKeyStep(rootKey: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = hkdf(rootKey, dhOut, INFO_ROOT, 2 * HASH_LEN)
        return okm.copyOfRange(0, HASH_LEN) to okm.copyOfRange(HASH_LEN, 2 * HASH_LEN)
    }

    fun chainKeyStep(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = hkdf(ByteArray(HASH_LEN), chainKey, INFO_CHAIN, 2 * HASH_LEN)
        return okm.copyOfRange(0, HASH_LEN) to okm.copyOfRange(HASH_LEN, 2 * HASH_LEN)
    }
}
