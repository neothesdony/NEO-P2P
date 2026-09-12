package com.neop2p.ui.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
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

    private const val TAG = "EvidenceImageExporter"

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

    /**
     * Save to the device gallery.
     *
     * API 29+: MediaStore insert with RELATIVE_PATH — no runtime permission
     * (that is the whole point of scoped storage).
     * API 26-28: public Pictures/<album> dir + media scan — the caller must
     * hold the runtime WRITE_EXTERNAL_STORAGE grant the manifest already
     * declares with maxSdkVersion=29.
     */
    fun saveToGallery(context: Context, bytes: ByteArray, fileName: String): Result<Uri> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val pending = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_JPEG)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + GALLERY_ALBUM
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
                ?: error("MediaStore insert returned null")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("MediaStore openOutputStream returned null")
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                // A half-written row stays invisible in the gallery but still
                // occupies the album — drop it instead of leaving the litter.
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val album = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                GALLERY_ALBUM
            )
            if (!album.exists() && !album.mkdirs()) error("Cannot create " + album.absolutePath)
            val file = File(album, fileName)
            file.outputStream().use { it.write(bytes) }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(MIME_JPEG), null)
            Uri.fromFile(file)
        }
    }.onFailure { Log.w(TAG, "saveToGallery($fileName) failed: ${it.message}") }

    /**
     * Hand the image to the system share sheet via FileProvider.
     *
     * Reuses the authority + cache dir the evidence export already exposes in
     * res/xml/file_paths.xml (<cache-path name="evidence/" />), so sharing
     * needs no manifest change.
     */
    fun shareImage(context: Context, bytes: ByteArray, fileName: String): Result<Unit> = runCatching {
        val dir = File(context.cacheDir, "evidence").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_JPEG
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, null)
        // Compose's LocalContext is normally the Activity, but a themed wrapper
        // would need NEW_TASK or startActivity throws.
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.onFailure { Log.w(TAG, "shareImage($fileName) failed: ${it.message}") }
}
