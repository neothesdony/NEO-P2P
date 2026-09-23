package com.neop2p.data.escrow

import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.base.SegwitAddress
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.crypto.internal.CryptoUtils
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
    data class Verdict(
        val arbKeyInScript: Boolean,
        val addressMatches: Boolean,
        val scriptIs2of3: Boolean,
        val templateMatches: Boolean = false,
    ) {
        val ok: Boolean get() = arbKeyInScript && addressMatches && scriptIs2of3 && templateMatches
    }

    fun verify(
        redeemScriptHex: String,
        fundingAddress: String,
        scriptType: String,
        expectedArbPubKeyHex: String,
        net: NetworkParameters,
        expectedTemplate: EscrowScriptTemplate = EscrowScriptTemplate.MULTISIG_2OF3_V0
    ): Verdict {
        return try {
            val program = hexToBytes(redeemScriptHex)
            if (program.isEmpty()) return Verdict(false, false, false)
            val script = Script(program)
            val expectedXOnly = xOnly(expectedArbPubKeyHex)
            val templateMatches = EscrowScriptTemplate.detect(program) == expectedTemplate
            val (arbKeyInScript, scriptIs2of3) = when (expectedTemplate) {
                EscrowScriptTemplate.MULTISIG_2OF3_V0 -> try {
                    val keys = script.pubKeys
                    val arb = keys.any { xOnly(it.publicKeyAsHex) == expectedXOnly }
                    arb to (script.numberOfSignaturesRequiredToSpend == 2 && keys.size == 3)
                } catch (_: Exception) {
                    false to false
                }
                EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1 -> try {
                    val keys = committedKeyXOnly(script)
                    val arb = expectedXOnly.isNotEmpty() && keys.any { it == expectedXOnly }
                    val structural = templateMatches && keys.distinct().size >= 3
                    (arb && structural) to (arb && structural)
                } catch (_: Exception) {
                    false to false
                }
            }
            val derived = when (scriptType.uppercase()) {
                "SEGWIT" -> SegwitAddress.fromProgram(net, 0, Sha256Hash.hash(program)).toBech32()
                else -> LegacyAddress.fromScriptHash(net, CryptoUtils.sha256hash160(program)).toBase58()
            }
            Verdict(
                arbKeyInScript,
                derived.equals(fundingAddress.trim(), ignoreCase = true),
                scriptIs2of3,
                templateMatches
            )
        } catch (e: Exception) {
            Verdict(false, false, false)
        }
    }

    /**
     * True when [pubKeyHex] (compressed or x-only) occupies a slot of the
     * 2-of-3 [redeemScriptHex]. This is the F2 on-chain anchor: a role-signed
     * destination attestation is only trusted when the signing key is actually
     * committed in the already-funded escrow script — an attacker can sign with
     * their own key, but cannot make that key appear in a script whose hash is
     * the funded address.
     */
    fun containsKey(redeemScriptHex: String, pubKeyHex: String): Boolean {
        return try {
            val program = hexToBytes(redeemScriptHex)
            if (program.isEmpty()) return false
            val expectedXOnly = xOnly(pubKeyHex)
            expectedXOnly.isNotEmpty() &&
                committedKeyXOnly(Script(program)).contains(expectedXOnly)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * x-only hex of every 33-byte compressed pubkey committed in [script].
     * `Script.pubKeys` throws on the V1 CLTV template (the 2-of-3 sits behind
     * OP_ELSE), so key anchors are resolved by scanning raw chunks instead.
     */
    internal fun committedKeyXOnly(script: Script): List<String> =
        script.chunks.mapNotNull { chunk ->
            val data = chunk.data ?: return@mapNotNull null
            if (data.size == 33 && (data[0].toInt() and 0xff) in 0x02..0x03) {
                data.copyOfRange(1, 33).toHex()
            } else {
                null
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
