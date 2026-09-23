package com.neop2p.data.p2p.ratchet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Wire framing for one ratchet message (E2EE v2, 2026-09-23):
 *
 *   magic("NP2R") ‖ version(1) ‖ header ‖ [ciphertext]
 *
 * The header (dhPub 32B, msgNum 8B, prevChainLength 8B) is transmitted in the
 * clear but is covered by the AEAD's AAD, so any tampering fails decryption.
 * The header-only form carries the first `ratchet_init` shot.
 */
object RatchetEnvelope {
    private val MAGIC = byteArrayOf(0x4E, 0x50, 0x32, 0x52) // "NP2R"
    const val VERSION = 2

    fun encode(header: RatchetHeader, ciphertext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writePrefix(out)
        writeHeader(out, header)
        writeBytes(out, ciphertext)
        return out.toByteArray()
    }

    fun encodeHeader(header: RatchetHeader): ByteArray {
        val out = ByteArrayOutputStream()
        writePrefix(out)
        writeHeader(out, header)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Pair<RatchetHeader, ByteArray> {
        val input = ByteArrayInputStream(bytes)
        readPrefix(input)
        val header = readHeader(input)
        val body = readBytes(input) ?: throw IllegalArgumentException("Missing envelope body")
        return header to body
    }

    fun decodeHeader(bytes: ByteArray): RatchetHeader {
        val input = ByteArrayInputStream(bytes)
        readPrefix(input)
        return readHeader(input)
    }

    private fun writePrefix(out: ByteArrayOutputStream) {
        out.write(MAGIC)
        out.write(VERSION)
    }

    private fun readPrefix(input: ByteArrayInputStream) {
        val magic = ByteArray(MAGIC.size)
        if (input.read(magic) != MAGIC.size || !magic.contentEquals(MAGIC)) {
            throw IllegalArgumentException("Not a ratchet envelope")
        }
        if (input.read() != VERSION) throw IllegalArgumentException("Unsupported envelope version")
    }

    private fun writeHeader(out: ByteArrayOutputStream, header: RatchetHeader) {
        writeBytes(out, header.dhPub)
        writeLong(out, header.msgNum)
        writeLong(out, header.prevChainLength)
    }

    private fun readHeader(input: ByteArrayInputStream): RatchetHeader {
        val dhPub = readBytes(input) ?: throw IllegalArgumentException("Missing dhPub")
        val msgNum = readLong(input) ?: throw IllegalArgumentException("Missing msgNum")
        val prevChainLength = readLong(input) ?: throw IllegalArgumentException("Missing prevChainLength")
        return RatchetHeader(dhPub, msgNum, prevChainLength)
    }

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

    private fun readLong(input: ByteArrayInputStream): Long? {
        if (input.available() < 8) return null
        var value = 0L
        repeat(8) { value = (value shl 8) or input.read().toLong() }
        return value
    }
}
