package com.neop2p.data.p2p

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class IdentityBlob(
    val seedPhrase: List<String>,
    val peerId: String,
    val nostrPubkeyHex: String,
    val nostrPrivateKeyHex: String,
    val nickname: String,
    val lnNodeId: String
)

object IdentityBlobCodec {

    fun encode(blob: IdentityBlob): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, blob.seedPhrase.joinToString(" "))
        writeString(out, blob.peerId)
        writeString(out, blob.nostrPubkeyHex)
        writeString(out, blob.nostrPrivateKeyHex)
        writeString(out, blob.nickname)
        writeString(out, blob.lnNodeId)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): IdentityBlob {
        if (bytes.isEmpty()) throw IllegalArgumentException("Empty identity blob")
        val input = ByteArrayInputStream(bytes)
        try {
            val seedPhrase = readString(input)?.split(" ")?.filter { it.isNotEmpty() }
                ?: throw IllegalArgumentException("Missing seed phrase")
            val peerId = readString(input) ?: throw IllegalArgumentException("Missing peerId")
            val nostrPubkey = readString(input) ?: throw IllegalArgumentException("Missing nostr pubkey")
            val nostrPrivkey = readString(input) ?: throw IllegalArgumentException("Missing nostr privkey")
            val nickname = readString(input) ?: throw IllegalArgumentException("Missing nickname")
            val lnNodeId = readString(input) ?: throw IllegalArgumentException("Missing lnNodeId")
            return IdentityBlob(seedPhrase, peerId, nostrPubkey, nostrPrivkey, nickname, lnNodeId)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed identity blob", e)
        }
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        out.write((bytes.size ushr 24) and 0xFF)
        out.write((bytes.size ushr 16) and 0xFF)
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    private fun readString(input: ByteArrayInputStream): String? {
        if (input.available() < 4) return null
        val len = (input.read() shl 24) or (input.read() shl 16) or (input.read() shl 8) or input.read()
        if (len < 0 || len > input.available()) return null
        if (len == 0) return ""
        val buf = ByteArray(len)
        if (input.read(buf) != len) return null
        return String(buf, StandardCharsets.UTF_8)
    }
}
