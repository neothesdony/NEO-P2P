package com.neop2p.data.escrow

import org.bitcoinj.core.Transaction

/**
 * bitcoinj hex / x-only / raw-tx helpers shared by [EscrowService] and the
 * `:core` escrow signing types ([ArbitratorSigner], `EscrowTxBuilder`).
 *
 * Moved verbatim from `EscrowService` (Phase 0b) so there is exactly one
 * implementation of each conversion on the money path.
 */
object EscrowCodec {

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    /**
     * Convert a 32-byte x-only secp256k1 pubkey into a 33-byte compressed key
     * (0x02 prefix + x). Bitcoinj's ECKey.fromPublicOnly() requires a compressed
     * key; the arbitrator pubkey in NeoP2PConfig is stored x-only.
     */
    fun xOnlyToCompressed(xOnlyHex: String): ByteArray {
        val xOnly = hexToBytes(xOnlyHex)
        val pub = if (xOnly.size == 32) xOnly else {
            // Already compressed / uncompressed: pass through as-is.
            return xOnly
        }
        return byteArrayOf(0x02) + pub
    }

    /** x-only form: drop the 0x02/0x03 prefix byte of a compressed pubkey. */
    fun xOnlyOf(compressedPubkeyHex: String): String {
        val bytes = hexToBytes(compressedPubkeyHex)
        val pub = if (bytes.size == 33) bytes.copyOfRange(1, 33) else bytes
        return pub.joinToString("") { "%02x".format(it) }
    }

    /**
     * Parse a broadcast-ready transaction from raw hex. bitcoinj 0.17 removed
     * the `Transaction(NetworkParameters, byte[])` constructor; `Transaction.read`
     * is the supported entry point and does not need network params for the
     * sighash/verification logic used here.
     */
    fun parseTx(hex: String): Transaction =
        Transaction.read(java.nio.ByteBuffer.wrap(hexToBytes(hex)))

    /** Lexicographic (unsigned byte) comparison — mirrors ECKey.PUBKEY_COMPARATOR. */
    fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val ai = a[i].toInt() and 0xff
            val bi = b[i].toInt() and 0xff
            if (ai != bi) return ai - bi
        }
        return a.size - b.size
    }
}
