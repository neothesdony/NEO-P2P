package com.neop2p.data.p2p.ratchet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * v2 pre-key bundle codec (E2EE v2, 2026-09-23). Magic "NP2R" + version 2.
 * A bundle without the magic is a legacy v1 bundle: the caller must fail closed
 * with [PeerMustUpgradeException] rather than downgrade.
 */
object PreKeyBundleCodec {
    private val MAGIC = byteArrayOf(0x4E, 0x50, 0x32, 0x52) // "NP2R"
    const val VERSION = 2

    fun encode(bundle: RatchetPreKeyBundle): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(VERSION)
        writeBytes(out, bundle.ikPub)
        writeBytes(out, bundle.spkPub)
        writeBytes(out, bundle.spkSignature)
        writeBytes(out, bundle.identityPubKey)
        writeBytes(out, bundle.identitySignature)
        return out.toByteArray()
    }

    fun isLegacy(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return true
        return !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
    }

    fun decode(bytes: ByteArray): RatchetPreKeyBundle {
        if (isLegacy(bytes)) {
            throw PeerMustUpgradeException("Peer sent a legacy E2EE v1 pre-key bundle — both peers must upgrade")
        }
        val input = ByteArrayInputStream(bytes)
        val magic = ByteArray(MAGIC.size)
        input.read(magic)
        if (input.read() != VERSION) {
            throw PeerMustUpgradeException("Unsupported E2EE bundle version")
        }
        val ikPub = readBytes(input) ?: throw IllegalArgumentException("Missing ikPub")
        val spkPub = readBytes(input) ?: throw IllegalArgumentException("Missing spkPub")
        val spkSignature = readBytes(input) ?: throw IllegalArgumentException("Missing spkSignature")
        val identityPubKey = readBytes(input) ?: throw IllegalArgumentException("Missing identityPubKey")
        val identitySignature = readBytes(input) ?: throw IllegalArgumentException("Missing identitySignature")
        return RatchetPreKeyBundle(ikPub, spkPub, spkSignature, identityPubKey, identitySignature)
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
