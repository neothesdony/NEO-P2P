package com.neop2p

/**
 * Minimal log-sink port so :core can log without android.util.Log.
 * The host wires [sink] at startup (:app -> Logcat, the admin daemon -> stdout).
 * Default is a no-op, which keeps plain-JVM tests silent.
 */
object NeoLog {
    enum class Level { INFO, WARN }

    @Volatile
    var sink: (Level, String, String, Throwable?) -> Unit = { _, _, _, _ -> }

    fun i(tag: String, message: String) = sink(Level.INFO, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        sink(Level.WARN, tag, message, throwable)
}
