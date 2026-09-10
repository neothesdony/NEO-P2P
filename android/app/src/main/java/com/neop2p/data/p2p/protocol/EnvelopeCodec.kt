package com.neop2p.data.p2p.protocol

import com.neop2p.data.p2p.P2PTransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object EnvelopeCodec {

    fun encode(msg: AppMessage): P2PTransport.TransportMessage {
        val out = ByteArrayOutputStream()
        writeString(out, msg.type)
        when (msg) {
            is AppMessage.PreKeyRequest -> {
                // no payload fields
            }
            is AppMessage.PreKeyBundle -> {
                writeBytes(out, msg.bundle)
            }
            is AppMessage.Chat -> {
                writeString(out, msg.offerId)
                writeBytes(out, msg.ciphertext)
            }
            is AppMessage.Offer -> {
                writeString(out, msg.offerJson)
            }
        }
        return P2PTransport.TransportMessage(
            type = msg.type,
            fromPeerId = "",
            toPeerId = msg.to,
            data = out.toByteArray()
        )
    }

    fun decode(env: P2PTransport.TransportMessage): AppMessage? {
        if (env.data.isEmpty()) return null
        return try {
            val input = ByteArrayInputStream(env.data)
            val type = readString(input) ?: return null
            val to = env.toPeerId
            val from = env.fromPeerId
            when (type) {
                "pre_key_request" -> AppMessage.PreKeyRequest(to, from)
                "pre_key_bundle" -> AppMessage.PreKeyBundle(to, readBytes(input) ?: return null, from)
                "chat" -> AppMessage.Chat(
                    to,
                    readString(input) ?: return null,
                    readBytes(input) ?: return null,
                    from
                )
                "offer" -> AppMessage.Offer(to, readString(input) ?: return null, from)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) =
        writeBytes(out, value.toByteArray(StandardCharsets.UTF_8))

    private fun writeBytes(out: ByteArrayOutputStream, value: ByteArray) {
        writeInt(out, value.size)
        out.write(value)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readString(input: ByteArrayInputStream): String? =
        readBytes(input)?.toString(StandardCharsets.UTF_8)

    private fun readBytes(input: ByteArrayInputStream): ByteArray? {
        val len = readInt(input) ?: return null
        if (len < 0 || len > input.available()) return null
        val buf = ByteArray(len)
        if (input.read(buf) != len) return null
        return buf
    }

    private fun readInt(input: ByteArrayInputStream): Int? {
        if (input.available() < 4) return null
        return (input.read() shl 24) or (input.read() shl 16) or (input.read() shl 8) or input.read()
    }
}
