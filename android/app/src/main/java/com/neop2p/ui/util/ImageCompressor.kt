package com.neop2p.ui.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Shared image compression for evidence payloads (payment receipts, dispute
 * evidence). Decodes + downsamples to ≤[MAX_IMAGE_EDGE]px edge and re-encodes
 * as a ≤[MAX_IMAGE_BYTES] JPEG so relay events and E2EE payloads stay small.
 */
object ImageCompressor {

    /** Max edge for the receipt screenshot before JPEG compression. */
    const val MAX_IMAGE_EDGE = 1600

    /** Max encoded size for the compressed JPEG. */
    const val MAX_IMAGE_BYTES = 60 * 1024

    /**
     * Decode + downsample [uri] to ≤1600px and re-encode as a ≤60KB JPEG,
     * returning the raw bytes (null when unreadable or still too large).
     */
    fun compressToBytes(resolver: ContentResolver, uri: Uri): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (edge / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching null
        val out = ByteArrayOutputStream()
        var quality = 85
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        // Tighten quality until we fit the 60KB cap (keeps the E2EE payload small).
        while (out.size() > MAX_IMAGE_BYTES && quality > 30) {
            out.reset()
            quality -= 10
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        val bytes = out.toByteArray()
        bitmap.recycle()
        if (bytes.size > MAX_IMAGE_BYTES) return@runCatching null
        bytes
    }.getOrNull()

    /** [compressToBytes] then base64 (NO_WRAP), or null. */
    fun compressToBase64(resolver: ContentResolver, uri: Uri): String? =
        compressToBytes(resolver, uri)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
}
