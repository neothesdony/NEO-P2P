package com.neop2p.ui.util

import java.security.MessageDigest
import java.util.Base64

/**
 * Save/share helpers for evidence images in the arbitrator dispute feed.
 *
 * Evidence arrives as base64 of a JPEG that was ALREADY downscaled to
 * <=1600px / <=60KB at submit time ([ImageCompressor]), so saving is a
 * byte-for-byte write — never a second lossy round-trip.
 *
 * The pure half ([fileName], [contentHash], [decode]) is plain-JVM and unit
 * tested. The Android half ([saveToGallery], [shareImage]) touches real
 * MediaStore/FileProvider APIs and is verified by hand on a device.
 */
object EvidenceImageExporter {

    /** Gallery album under Pictures/. */
    const val GALLERY_ALBUM = "NEO-P2P"
    const val MIME_JPEG = "image/jpeg"

    /**
     * Deterministic file name: `neop2p-evidence-<escrow8>-<index>-<hash8>.jpg`.
     *
     * The escrowId arrives from the wire, so it is stripped to alphanumerics
     * and truncated before it can reach MediaStore or the filesystem. The
     * SHA-256 prefix keeps two different images at the same list index apart.
     */
    fun fileName(escrowId: String, index: Int, bytes: ByteArray): String {
        val safeId = escrowId.filter { it.isLetterOrDigit() }.take(8).ifBlank { "escrow" }
        return "neop2p-evidence-$safeId-$index-${contentHash(bytes)}.jpg"
    }

    /** First 8 hex chars of SHA-256 — enough to separate evidence images by content. */
    fun contentHash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(4)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * MIME-lenient base64 decode: ignores line wrapping and foreign characters
     * the way `android.util.Base64.DEFAULT` did, but this is a plain-JVM API,
     * so the behaviour is unit-testable. Returns null for empty/undecodable input.
     */
    fun decode(imageBase64: String): ByteArray? = runCatching {
        Base64.getMimeDecoder().decode(imageBase64)
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
