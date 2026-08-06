package com.neop2p.data.p2p

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Binary framing for WebRTC signaling messages (SDP offer/answer, ICE candidates)
 * exchanged over the libp2p transport. Same length-prefixed style as [com.neop2p.data.p2p.protocol.EnvelopeCodec].
 *
 * Layout: [type:1 byte][sdpMid:len-prefixed string][sdpMLineIndex:4 bytes][payload:len-prefixed bytes]
 * Types: 1 = offer, 2 = answer, 3 = ice candidate.
 */
object WebRTCSignalCodec {

    private const val TYPE_OFFER = 1
    private const val TYPE_ANSWER = 2
    private const val TYPE_ICE = 3

    fun encodeOffer(sdp: String): ByteArray = encode(TYPE_OFFER, sdp, null, null)

    fun encodeAnswer(sdp: String): ByteArray = encode(TYPE_ANSWER, sdp, null, null)

    fun encodeIceCandidate(candidate: String, sdpMid: String? = null, sdpMLineIndex: Int? = null): ByteArray =
        encode(TYPE_ICE, candidate, sdpMid, sdpMLineIndex)

    fun decodeOffer(bytes: ByteArray): String? = decode(bytes, TYPE_OFFER)?.first

    fun decodeAnswer(bytes: ByteArray): String? = decode(bytes, TYPE_ANSWER)?.first

    fun decodeIceCandidate(bytes: ByteArray): String? = decode(bytes, TYPE_ICE)?.first

    private fun encode(type: Int, payload: String, sdpMid: String?, sdpMLineIndex: Int?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type)
        writeString(out, sdpMid ?: "")
        writeInt(out, sdpMLineIndex ?: -1)
        writeBytes(out, payload.toByteArray(StandardCharsets.UTF_8))
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray, expectedType: Int): Pair<String, String?>? {
        if (bytes.isEmpty()) return null
        return try {
            val input = ByteArrayInputStream(bytes)
            val type = input.read()
            if (type != expectedType) return null
            val sdpMid = readString(input) ?: return null
            val mline = readInt(input) ?: return null
            val payload = readBytes(input) ?: return null
            if (input.available() != 0) return null
            payload.toString(StandardCharsets.UTF_8) to sdpMid
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
