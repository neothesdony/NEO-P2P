package com.neop2p.data.escrow

import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.SegwitAddress
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Utils
import org.bitcoinj.script.Script

/**
 * Escrow script attestation gate (F3, 2026-09-12).
 *
 * The script is built only on the creator's device. Every receiver verifies,
 * from data it already holds (redeem_script_hex + funding_address from the
 * escrow_status sync):
 *  1. the official arbitrator key occupies a slot of the 2-of-3 script;
 *  2. the script hashes to the advertised funding address (P2SH hash160 /
 *     P2WSH sha256) — a lying script can no longer masquerade.
 * A tampered counterparty build that swaps in a self-controlled tie-break
 * key is caught before the buyer sends any fiat.
 */
object EscrowScriptGate {
    data class Verdict(val arbKeyInScript: Boolean, val addressMatches: Boolean) {
        val ok: Boolean get() = arbKeyInScript && addressMatches
    }

    fun verify(
        redeemScriptHex: String,
        fundingAddress: String,
        scriptType: String,
        expectedArbPubKeyHex: String,
        net: NetworkParameters
    ): Verdict {
        return try {
            val program = hexToBytes(redeemScriptHex)
            if (program.isEmpty()) return Verdict(false, false)
            val script = Script(program)
            val expectedXOnly = xOnly(expectedArbPubKeyHex)
            val arbKeyInScript = try {
                script.pubKeys.any { xOnly(it.publicKeyAsHex) == expectedXOnly }
            } catch (_: Exception) {
                false
            }
            val derived = when (scriptType.uppercase()) {
                "SEGWIT" -> SegwitAddress.fromProgram(net, 0, Sha256Hash.hash(program)).toBech32()
                else -> LegacyAddress.fromScriptHash(net, Utils.sha256hash160(program)).toBase58()
            }
            Verdict(arbKeyInScript, derived.equals(fundingAddress.trim(), ignoreCase = true))
        } catch (e: Exception) {
            Verdict(false, false)
        }
    }

    private fun xOnly(pubHex: String): String {
        val bytes = hexToBytes(pubHex)
        val x = if (bytes.size == 33) bytes.copyOfRange(1, 33) else bytes
        return x.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.length % 2 != 0) return ByteArray(0)
        val data = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            if (hi < 0 || lo < 0) return ByteArray(0)
            data[i / 2] = ((hi shl 4) + lo).toByte()
        }
        return data
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
