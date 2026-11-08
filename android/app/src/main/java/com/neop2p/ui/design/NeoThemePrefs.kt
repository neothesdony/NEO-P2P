package com.neop2p.ui.design

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** How the app picks light or dark. `SYSTEM` follows the device setting. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * One tap on the settings row walks the three modes. A three-way radio would also
 * work, but a single row that cycles is one target on a small screen and keeps the
 * current value visible without opening a dialog.
 */
fun ThemeMode.next(): ThemeMode = when (this) {
    ThemeMode.SYSTEM -> ThemeMode.DARK
    ThemeMode.DARK -> ThemeMode.LIGHT
    ThemeMode.LIGHT -> ThemeMode.SYSTEM
}

/**
 * Pure resolution so the table is JVM-testable and the Compose layer never has to
 * know about `Configuration` or `isSystemInDarkTheme()` internals.
 */
fun resolveDarkTheme(mode: ThemeMode, systemIsDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemIsDark
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

/**
 * The theme choice is **not** behind the identity unlock gate — a locked phone must
 * still render in the user's chosen scheme. Plain SharedPreferences, no
 * encryption, nothing sensitive in it.
 */
class NeoThemePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(): ThemeMode = read()

    fun set(mode: ThemeMode) {
        prefs.edit { putString(KEY_MODE, mode.name) }
    }

    /**
     * Emits on every change to the stored mode, from ANY instance.
     *
     * Backed by a SharedPreferences listener rather than an in-memory flow: the
     * activity and the settings screen hold separate instances, and an
     * instance-local StateFlow would never wake the activity when settings wrote.
     */
    fun observe(): Flow<ThemeMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_MODE) trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun read(): ThemeMode {
        val raw = prefs.getString(KEY_MODE, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
    }

    private companion object {
        const val PREFS = "neo_theme"
        const val KEY_MODE = "mode"
    }
}
