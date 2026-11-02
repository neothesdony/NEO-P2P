package com.neop2p.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.neop2p.BuildConfig
import com.neop2p.data.diagnostics.CrashLogPolicy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local, privacy-preserving crash log (M-10, 2026-11-03).
 *
 * Wraps the default uncaught-exception handler, writes one redacted file per
 * crash under `filesDir/crash/`, keeps at most [CrashLogPolicy.MAX_FILES], and
 * then delegates to the previous handler. No network, no analytics: the user
 * exports the log explicitly from Settings.
 */
object CrashLogHandler {
    private const val DIR_NAME = "crash"
    private const val EXPORT_NAME = "crash-export.txt"
    private const val MAX_CHARS = 256 * 1024

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** Crash files, oldest first; the generated export is not a crash file. */
    fun files(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name != EXPORT_NAME }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Share sheet for every stored crash, or null when there is nothing to send. */
    fun shareIntent(context: Context): Intent? {
        val logs = files(context)
        if (logs.isEmpty()) return null
        val export = File(dir(context), EXPORT_NAME)
        export.writeText(logs.joinToString("\n\n") { it.readText() })
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", export
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun write(context: Context, throwable: Throwable) {
        val dir = dir(context)
        val existing = files(context)
        if (CrashLogPolicy.shouldRotate(existing.size)) {
            // Make room for exactly one new file.
            existing.take(existing.size - CrashLogPolicy.MAX_FILES + 1).forEach { it.delete() }
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val body = buildString {
            appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("model=${Build.MODEL}")
            appendLine("api=${Build.VERSION.SDK_INT}")
            appendLine(CrashLogPolicy.redact(throwable.stackTraceToString()))
        }
        File(dir, "$stamp.txt").writeText(body.take(MAX_CHARS))
    }
}
