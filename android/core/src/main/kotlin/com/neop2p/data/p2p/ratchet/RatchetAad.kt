package com.neop2p.data.p2p.ratchet

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Canonical associated data for one ratchet message (E2EE v2, 2026-09-23). */
data class RatchetHeader(
    val dhPub: ByteArray,
    val msgNum: Long,
    val prevChainLength: Long,
)

object RatchetAad {
    const val VERSION = 2

    fun build(sessionId: String, fromPeerId: String, offerId: String, header: RatchetHeader): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(VERSION)
        writeString(out, sessionId)
        writeString(out, fromPeerId)
        writeString(out, offerId)
        writeBytes(out, header.dhPub)
        writeLong(out, header.msgNum)
        writeLong(out, header.prevChainLength)
        return out.toByteArray()
    }

    /** A symmetric session id both peers can compute: sorted peerId pair. */
    fun sessionId(peerA: String, peerB: String): String =
        if (peerA <= peerB) "$peerA|$peerB" else "$peerB|$peerA"

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

    private fun writeLong(out: ByteArrayOutputStream, value: Long) {
        for (shift in 56 downTo 0 step 8) out.write(((value ushr shift) and 0xFF).toInt())
    }
}
