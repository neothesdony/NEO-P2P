package com.neop2p.data.escrow

import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash

/**
 * Role-signed address attestation (F2, 2026-09-12).
 *
 * Binds a destination address to the escrow ROLE KEY that must receive the funds:
 *  - KIND_SELLER_REFUND: signed by the seller's escrow key, scope = escrowId.
 *  - KIND_BUYER_PAYOUT:  signed by the buyer's escrow key, scope = offerId.
 *
 * Message: "neop2p-attest-v1|<kind>|<scopeId>|<address>", signed with ECDSA
 * over SHA-256(message), DER-encoded (bitcoinj ECKey). Callers verify the
 * pubkey against the escrow row / redeem script — this codec only proves
 * "the holder of <pubkey> bound <address>".
 */
object RoleAddressAttestation {
    const val KIND_SELLER_REFUND = "refund"
    const val KIND_BUYER_PAYOUT = "payout"
    private const val PREFIX = "neop2p-attest-v1"

    fun message(kind: String, scopeId: String, address: String): ByteArray =
        "$PREFIX|$kind|$scopeId|$address".toByteArray(Charsets.UTF_8)

    fun sign(privateKeyHex: String, kind: String, scopeId: String, address: String): String {
        val key = ECKey.fromPrivate(hexToBytes(privateKeyHex))
        val hash = Sha256Hash.wrap(Sha256Hash.hash(message(kind, scopeId, address)))
        return key.sign(hash).encodeToDER().joinToString("") { "%02x".format(it) }
    }

    fun verify(publicKeyHex: String, kind: String, scopeId: String, address: String, sigHex: String): Boolean {
        return try {
            val hash = Sha256Hash.wrap(Sha256Hash.hash(message(kind, scopeId, address)))
            val sig = ECKey.ECDSASignature.decodeFromDER(hexToBytes(sigHex))
            candidates(publicKeyHex).any { ECKey.fromPublicOnly(it).verify(hash, sig) }
        } catch (e: Exception) {
            false
        }
    }

    /** x-only (32B) is ambiguous about Y parity — try both; pass-through otherwise. */
    private fun candidates(pubHex: String): List<ByteArray> {
        val raw = hexToBytes(pubHex)
        return if (raw.size == 32) listOf(byteArrayOf(0x02) + raw, byteArrayOf(0x03) + raw) else listOf(raw)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}
