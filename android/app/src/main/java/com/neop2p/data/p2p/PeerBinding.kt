package com.neop2p.data.p2p

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Lowercase hex encoding of a byte array. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Peer identity binding (F1, 2026-09-12).
 *
 * The RNS peerId is an LXMF announce displayName — a self-asserted string.
 * This codec binds it cryptographically: the holder of the libp2p Ed25519 key
 * (m/44'/888'/0'/0/0) signs "neop2p-binding-v1|<peerId>|<rnsIdentityHash>|<destHash>".
 * A verifier proves: (a) the claimed peerId derives from the signing key, and
 * (b) THE RNS identity announcing it is the one covered by the signature.
 */
object PeerBinding {
    private const val PREFIX = "neop2p-binding-v1"

    fun message(peerId: String, identityHashHex: String, destHashHex: String): ByteArray =
        "$PREFIX|$peerId|$identityHashHex|$destHashHex".toByteArray(Charsets.UTF_8)

    fun sign(libp2pPrivKey: ByteArray, message: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(libp2pPrivKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature().toHex()
    }

    fun verify(libp2pPubHex: String, sigHex: String, peerId: String, identityHashHex: String, destHashHex: String): Boolean {
        return try {
            val pub = hexToBytes(libp2pPubHex)
            if (pub.size != 32) return false
            if (!KeyDerivation.deriveLibp2pPeerIdFromPublicKey(pub).equals(peerId, ignoreCase = true)) return false
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(pub, 0))
            val msg = message(peerId, identityHashHex, destHashHex)
            verifier.update(msg, 0, msg.size)
            verifier.verifySignature(hexToBytes(sigHex))
        } catch (e: Exception) {
            false
        }
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
