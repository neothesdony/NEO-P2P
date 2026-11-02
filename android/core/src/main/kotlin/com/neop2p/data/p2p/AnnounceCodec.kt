package com.neop2p.data.p2p

import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

/**
 * Bounded parser for RNS announce `appData` (H-1, 2026-11-03).
 *
 * The delivery announce is `[displayName(bin), stampCost]` and is emitted by
 * any RNS identity, so the declared binary length must be checked against a
 * cap BEFORE the buffer is allocated: a hostile 7-byte payload can declare a
 * ~2 GB `bin32` length, and an `OutOfMemoryError` is not caught by the
 * `catch (Exception)` around the announce handler.
 *
 * The identity binding announce is `[peerId(str), pubKeyHex(str),
 * signatureHex(str)]`; `unpackString()` already checks `available()` in
 * msgpack 0.9.8, but the explicit cap keeps the parser bounded even if the
 * library behaviour changes.
 */
object AnnounceCodec {
    const val MAX_PEER_ID_BYTES = 128
    const val MAX_IDENTITY_FIELD_BYTES = 256

    data class IdentityFields(val peerId: String, val pubKeyHex: String, val signatureHex: String)

    /** `[displayName(bin), stampCost]` → the announced peerId, or null when malformed. */
    fun peerIdFromDelivery(appData: ByteArray): String? = runCatching {
        MessagePack.newDefaultUnpacker(appData).use { u ->
            val size = u.unpackArrayHeader()
            if (size !in 1..2) return null
            if (u.tryUnpackNil()) return null
            readBinaryString(u, MAX_PEER_ID_BYTES)?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** `[peerId, pubKeyHex, signatureHex]` → the binding fields, or null when malformed. */
    fun identityFields(appData: ByteArray): IdentityFields? = runCatching {
        MessagePack.newDefaultUnpacker(appData).use { u ->
            if (u.unpackArrayHeader() != 3) return null
            val peerId = readString(u, MAX_PEER_ID_BYTES) ?: return null
            val pub = readString(u, MAX_IDENTITY_FIELD_BYTES) ?: return null
            val sig = readString(u, MAX_IDENTITY_FIELD_BYTES) ?: return null
            IdentityFields(peerId, pub, sig)
        }
    }.getOrNull()

    private fun readBinaryString(u: MessageUnpacker, max: Int): String? {
        val len = u.unpackBinaryHeader()
        if (len < 0 || len > max) return null
        val bytes = ByteArray(len)
        u.readPayload(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readString(u: MessageUnpacker, max: Int): String? {
        val len = u.unpackRawStringHeader()
        if (len < 0 || len > max) return null
        val bytes = ByteArray(len)
        u.readPayload(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
