package com.neop2p.ui.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the evidence image naming + decode half of
 * [EvidenceImageExporter]. The MediaStore/FileProvider half needs a real
 * device and is covered by the manual smoke test in Task 4.
 *
 * The escrowId travels in from the wire, so the filename must be safe to hand
 * to MediaStore / the filesystem no matter what it contains.
 */
class EvidenceImageExporterTest {

    private val jpegA = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)
    private val jpegB = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x03, 0x04)

    @Test
    fun `fileName carries escrow prefix, index and hash`() {
        val name = EvidenceImageExporter.fileName("a1b2c3d4e5f6", 0, jpegA)
        assertTrue(name, name.startsWith("neop2p-evidence-a1b2c3d4-0-"))
        assertTrue(name, name.endsWith(".jpg"))
    }

    @Test
    fun `fileName sanitizes a path-traversal escrow id`() {
        // "../../etc/passwd" -> alphanumerics only -> "etcpasswd" -> take(8) -> "etcpassw"
        val name = EvidenceImageExporter.fileName("../../etc/passwd", 2, jpegA)
        assertTrue(name, name.startsWith("neop2p-evidence-etcpassw-2-"))
        assertFalse(name, name.contains("/"))
        assertFalse(name, name.contains(".."))
    }

    @Test
    fun `fileName falls back to escrow when the id has nothing usable`() {
        val name = EvidenceImageExporter.fileName("///", 0, jpegA)
        assertTrue(name, name.startsWith("neop2p-evidence-escrow-0-"))
    }

    @Test
    fun `fileName distinguishes different images at the same index`() {
        assertNotEquals(
            EvidenceImageExporter.fileName("abc12345", 0, jpegA),
            EvidenceImageExporter.fileName("abc12345", 0, jpegB)
        )
    }

    @Test
    fun `fileName is stable for identical input`() {
        assertEquals(
            EvidenceImageExporter.fileName("abc12345", 1, jpegA),
            EvidenceImageExporter.fileName("abc12345", 1, jpegA.copyOf())
        )
    }

    @Test
    fun `contentHash is 8 lowercase hex chars`() {
        val hash = EvidenceImageExporter.contentHash(jpegA)
        assertEquals(8, hash.length)
        assertTrue(hash, hash.matches(Regex("[0-9a-f]{8}")))
    }

    @Test
    fun `decode round-trips wrapped base64`() {
        val raw = ByteArray(300) { (it % 251).toByte() }
        val wrapped = java.util.Base64.getEncoder().encodeToString(raw).chunked(64).joinToString("\n")
        assertArrayEquals(raw, EvidenceImageExporter.decode(wrapped))
    }

    @Test
    fun `decode returns null for empty input`() {
        assertNull(EvidenceImageExporter.decode(""))
    }

    @Test
    fun `decode returns null for input with no base64 content`() {
        assertNull(EvidenceImageExporter.decode("!!!!"))
    }
}
