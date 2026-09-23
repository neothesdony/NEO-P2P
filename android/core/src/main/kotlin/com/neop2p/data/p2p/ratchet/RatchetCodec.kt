package com.neop2p.data.p2p.ratchet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Versioned binary codec for [RatchetState]. Magic "RAT" + version 1. Every
 * byte array is 4-byte big-endian length-prefixed (consistent with
 * EnvelopeCodec / SignalProtocol's bundle framing).
 */
object RatchetCodec {
    private val MAGIC = byteArrayOf(0x52, 0x41, 0x54) // "RAT"
    private const val VERSION = 1

    fun encode(state: RatchetState): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(VERSION)
        writeBytes(out, state.rootKey)
        writeBytes(out, state.dhSelfPriv)
        writeBytes(out, state.dhSelfPub)
        writeBytes(out, state.dhRemotePub)
        writeBytes(out, state.sendChainKey)
        writeLong(out, state.sendCount)
        writeBytes(out, state.recvChainKey)
        writeLong(out, state.recvCount)
        writeLong(out, state.prevChainLength)
        writeLong(out, state.recvEpoch)
        writeInt(out, state.skipped.size)
        for ((key, value) in state.skipped) {
            writeLong(out, key)
            writeBytes(out, value)
        }
        writeBytes(out, state.remoteInitialPub)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): RatchetState {
        val input = ByteArrayInputStream(bytes)
        val magic = ByteArray(MAGIC.size)
        if (input.read(magic) != MAGIC.size || !magic.contentEquals(MAGIC)) {
            throw IllegalArgumentException("Not a ratchet state blob")
        }
        if (input.read() != VERSION) throw IllegalArgumentException("Unsupported ratchet state version")
        val rootKey = readBytes(input) ?: throw IllegalArgumentException("Missing rootKey")
        val dhSelfPriv = readBytes(input) ?: throw IllegalArgumentException("Missing dhSelfPriv")
        val dhSelfPub = readBytes(input) ?: throw IllegalArgumentException("Missing dhSelfPub")
        val dhRemotePub = readBytes(input) ?: throw IllegalArgumentException("Missing dhRemotePub")
        val sendChainKey = readBytes(input) ?: throw IllegalArgumentException("Missing sendChainKey")
        val sendCount = readLong(input) ?: throw IllegalArgumentException("Missing sendCount")
        val recvChainKey = readBytes(input) ?: throw IllegalArgumentException("Missing recvChainKey")
        val recvCount = readLong(input) ?: throw IllegalArgumentException("Missing recvCount")
        val prevChainLength = readLong(input) ?: throw IllegalArgumentException("Missing prevChainLength")
        val recvEpoch = readLong(input) ?: throw IllegalArgumentException("Missing recvEpoch")
        val skippedCount = readInt(input) ?: throw IllegalArgumentException("Missing skippedCount")
        if (skippedCount < 0 || skippedCount > RatchetState.MAX_SKIPPED) {
            throw IllegalArgumentException("Skipped key count out of range: $skippedCount")
        }
        val skipped = LinkedHashMap<Long, ByteArray>(skippedCount)
        repeat(skippedCount) {
            val key = readLong(input) ?: throw IllegalArgumentException("Missing skipped key")
            val value = readBytes(input) ?: throw IllegalArgumentException("Missing skipped value")
            skipped[key] = value
        }
        val remoteInitialPub = readBytes(input) ?: throw IllegalArgumentException("Missing remoteInitialPub")
        return RatchetState(
            rootKey, dhSelfPriv, dhSelfPub, dhRemotePub, sendChainKey, sendCount,
            recvChainKey, recvCount, prevChainLength, recvEpoch, skipped, remoteInitialPub
        )
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
