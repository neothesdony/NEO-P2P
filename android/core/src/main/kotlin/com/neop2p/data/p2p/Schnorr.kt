package com.neop2p.data.p2p

import java.math.BigInteger
import java.security.MessageDigest

/**
 * BIP-340 Schnorr signatures over secp256k1 (Nostr NIP-01).
 *
 * Pure Kotlin + Bouncy Castle — no JNI, works in JVM unit tests and on device.
 * Verified against the official BIP-340 test vectors.
 */
object Schnorr {

    private val P = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16
    )
    private val N = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16
    )
    private val GX = BigInteger(
        "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16
    )
    private val GY = BigInteger(
        "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16
    )

    private val curve by lazy {
        org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256k1").curve
    }
    private val G by lazy {
        curve.createPoint(GX, GY)
    }

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray())
        return MessageDigest.getInstance("SHA-256").digest(tagHash + tagHash + data)
    }

    private fun intToBytes32(i: BigInteger): ByteArray {
        val bytes = i.toByteArray()
        return if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size)
        else ByteArray(32 - bytes.size) + bytes
    }

    private fun liftX(x: BigInteger): org.bouncycastle.math.ec.ECPoint? {
        if (x >= P) return null
        val c = x.modPow(BigInteger.valueOf(3), P).add(BigInteger.valueOf(7)).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
        if (y.modPow(BigInteger.valueOf(2), P) != c) return null
        val point = curve.createPoint(x, if (y.testBit(0)) P.subtract(y) else y)
        return point
    }

    private fun hasEvenY(point: org.bouncycastle.math.ec.ECPoint): Boolean =
        !point.affineYCoord.toBigInteger().testBit(0)

    /**
     * BIP-340 public key (x-only 32 bytes) from a 32-byte secret key.
     */
    fun pubKey(sk: ByteArray): ByteArray {
        val d = BigInteger(1, sk)
        require(d != BigInteger.ZERO && d < N) { "Invalid secret key" }
        val point = G.multiply(d).normalize()
        return intToBytes32(point.affineXCoord.toBigInteger())
    }

    /**
     * BIP-340 sign. Deterministic given (sk, msg, auxRand).
     */
    fun sign(sk: ByteArray, msg: ByteArray, auxRand: ByteArray): ByteArray {
        val d0 = BigInteger(1, sk)
        require(d0 != BigInteger.ZERO && d0 < N) { "Invalid secret key" }
        val p = G.multiply(d0).normalize()
        val d = if (hasEvenY(p)) d0 else N.subtract(d0)

        val t = intToBytes32(d).zip(taggedHash("BIP0340/aux", auxRand))
            .map { (a, b) -> (a.toInt() xor b.toInt()).toByte() }.toByteArray()
        val rand = BigInteger(1, taggedHash("BIP0340/nonce", t + intToBytes32(p.affineXCoord.toBigInteger()) + msg))
            .mod(N)
        require(rand != BigInteger.ZERO) { "Invalid nonce" }

        val rPoint = G.multiply(rand).normalize()
        val k = if (hasEvenY(rPoint)) rand else N.subtract(rand)

        val e = BigInteger(1, taggedHash(
            "BIP0340/challenge",
            intToBytes32(rPoint.affineXCoord.toBigInteger()) +
                intToBytes32(p.affineXCoord.toBigInteger()) + msg
        )).mod(N)

        val s = k.add(e.multiply(d)).mod(N)
        return intToBytes32(rPoint.affineXCoord.toBigInteger()) + intToBytes32(s)
    }

    /**
     * BIP-340 verify. Returns false on any failure (never throws).
     */
    fun verify(pk: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (pk.size != 32 || sig.size != 64) return false
        return try {
            val p = liftX(BigInteger(1, pk)) ?: return false
            val r = BigInteger(1, sig.copyOfRange(0, 32))
            val s = BigInteger(1, sig.copyOfRange(32, 64))
            if (r >= P || s >= N) return false
            val e = BigInteger(1, taggedHash(
                "BIP0340/challenge",
                sig.copyOfRange(0, 32) + pk + msg
            )).mod(N)
            val rPoint = G.multiply(s).add(p.multiply(e).negate()).normalize()
            if (rPoint.isInfinity) return false
            if (!hasEvenY(rPoint)) return false
            rPoint.affineXCoord.toBigInteger() == r
        } catch (_: Exception) {
            false
        }
    }
}
