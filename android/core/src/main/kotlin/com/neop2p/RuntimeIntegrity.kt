package com.neop2p

/**
 * Runtime-integrity policy for money actions (F6, 2026-09-23).
 *
 * A release APK that can move BTC should refuse to do so while a debugger is
 * attached — that is an unambiguous tampering signal. Rooted devices, enabled
 * ADB/developer options, and emulators are suspicious but common among the
 * self-hosting audience, so they WARN rather than block. The inputs are passed
 * explicitly so the rule stays pure-JVM and unit-testable; the Android probe
 * (`RuntimeIntegrityProbe`) collects them.
 */
object RuntimeIntegrity {

    enum class Level { OK, WARN, BLOCK }

    data class Signals(
        val isDebuggable: Boolean,
        val isDebuggerAttached: Boolean,
        val isEmulator: Boolean,
        val isRooted: Boolean,
        val adbEnabled: Boolean,
        val devOptionsEnabled: Boolean,
    )

    const val ERR_RUNTIME_INTEGRITY: String = "ERR_RUNTIME_INTEGRITY"

    fun assess(signals: Signals): Level = when {
        signals.isDebuggerAttached -> Level.BLOCK
        signals.isDebuggable ||
            signals.isRooted ||
            signals.adbEnabled ||
            signals.devOptionsEnabled ||
            signals.isEmulator -> Level.WARN
        else -> Level.OK
    }

    fun blocked(signals: Signals): Boolean = assess(signals) == Level.BLOCK
}
