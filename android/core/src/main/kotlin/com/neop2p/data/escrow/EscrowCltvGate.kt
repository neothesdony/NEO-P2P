package com.neop2p.data.escrow

import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptChunk
import org.bitcoinj.script.ScriptOpCodes

/**
 * C9 counterparty gate (2026-09-24): the creator chooses `cltv_locktime`, so a
 * tampered seller client can commit an already-matured OP_IF branch and recover
 * the deposit after the buyer pays fiat. Every receiver must re-derive the
 * floor from the escrow's own `created_at` and verify the OP_IF branch is the
 * seller's key. Fails closed on anything unrecognised.
 *
 * Chunk layout produced by [EscrowScripts.build] (V1):
 *   0 OP_IF | 1 <locktime> | 2 OP_CLTV | 3 OP_DROP | 4 <sellerKey> | 5 OP_CHECKSIG | ...
 */
object EscrowCltvGate {
    data class Verdict(val locktime: Long?, val sellerKeyMatches: Boolean, val locktimeValid: Boolean) {
        val ok: Boolean get() = locktime != null && sellerKeyMatches && locktimeValid
    }

    fun verify(
        program: ByteArray,
        expectedSellerPubKeyHex: String,
        createdAtMs: Long,
        maturityMs: Long = EscrowScripts.MATURITY_MS,
    ): Verdict {
        return try {
            val chunks = Script(program).chunks
            if (chunks.size < 5) return Verdict(null, false, false)
            val locktime = numberFrom(chunks[1]) ?: return Verdict(null, false, false)
            val sellerXOnly = xOnlyOfCompressed(chunks[4].data) ?: return Verdict(locktime, false, false)
            val expected = xOnly(expectedSellerPubKeyHex)
            val matches = expected.isNotEmpty() && expected == sellerXOnly
            val minLock = (createdAtMs + maturityMs) / 1000
            Verdict(locktime, matches, locktime >= minLock)
        } catch (_: Exception) {
            Verdict(null, false, false)
        }
    }

    /**
     * The maturity floor the CLTV gate must anchor to (2026-09-26). The escrow's
     * own `created_at` is peer-supplied on a mirror and therefore forgeable — a
     * tampered seller can publish `created_at = locktime*1000 - MATURITY_MS` so a
     * matured OP_IF branch passes. The buyer's LOCAL offer `locked_at` (stamped
     * at match, Room v25) cannot be forged. The creator may use its own trusted
     * created_at. Null = no trustworthy anchor -> caller must fail closed.
     */
    fun trustedMaturityFloorMs(
        offerLockedAtMs: Long?,
        escrowCreatedAtMs: Long,
        localIsCreator: Boolean,
    ): Long? = when {
        offerLockedAtMs != null && offerLockedAtMs > 0L -> offerLockedAtMs
        localIsCreator -> escrowCreatedAtMs
        else -> null
    }

    /** Little-endian number from a push-data chunk, or OP_1..OP_16. Null otherwise. */
    private fun numberFrom(chunk: ScriptChunk): Long? {
        if (chunk.isPushData) {
            val d = chunk.data ?: return null
            if (d.isEmpty() || d.size > 5) return null
            var v = 0L
            for (i in d.indices) v = v or ((d[i].toLong() and 0xff) shl (8 * i))
            return v
        }
        if (chunk.opcode in ScriptOpCodes.OP_1..ScriptOpCodes.OP_16) {
            return (chunk.opcode - ScriptOpCodes.OP_1 + 1).toLong()
        }
        return null
    }

    private fun xOnlyOfCompressed(data: ByteArray?): String? {
        if (data == null || data.size != 33 || (data[0].toInt() and 0xff) !in 0x02..0x03) return null
        return data.copyOfRange(1, 33).toHex()
    }

    private fun xOnly(pubHex: String): String {
        val bytes = hexToBytes(pubHex) ?: return ""
        val x = if (bytes.size == 33) bytes.copyOfRange(1, 33) else bytes
        if (x.size != 32) return ""
        return x.toHex()
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            val hi = Character.digit(hex[i], 16); val lo = Character.digit(hex[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) + lo).toByte()
        }
        return out
    }
}
