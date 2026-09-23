package com.neop2p.data.security

import android.content.Context
import android.os.Build
import android.os.Debug
import android.provider.Settings
import com.neop2p.BuildConfig
import com.neop2p.RuntimeIntegrity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects the Android-side signals for [RuntimeIntegrity] (F6, 2026-09-23).
 * Heuristics only — the policy decision lives in the pure `:core` gate.
 */
@Singleton
class RuntimeIntegrityProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun assess(): RuntimeIntegrity.Signals = RuntimeIntegrity.Signals(
        isDebuggable = BuildConfig.DEBUG,
        isDebuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger(),
        isEmulator = isEmulator(),
        isRooted = isRooted(),
        adbEnabled = globalFlag(Settings.Global.ADB_ENABLED),
        devOptionsEnabled = globalFlag(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
    )

    private fun globalFlag(key: String): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, key, 0) == 1 }.getOrDefault(false)

    private fun isEmulator(): Boolean {
        val fp = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        return fp.startsWith("generic") ||
            fp.contains("emulator") ||
            model.contains("google_sdk") ||
            model.contains("emulator") ||
            model.contains("android sdk built for") ||
            product.contains("sdk") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu")
    }

    private fun isRooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        val paths = listOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su",
        )
        return paths.any { runCatching { File(it).exists() }.getOrDefault(false) }
    }
}
