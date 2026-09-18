package com.neop2p.admind

import com.neop2p.NeoLog
import org.slf4j.LoggerFactory

/**
 * Routes `:core`'s [NeoLog] port to SLF4J for the headless daemon. The app installs
 * an Android `Log` sink; without this the daemon's `:core` logging would be silent.
 */
object NeoLogSlf4j {

    fun install() {
        val log = LoggerFactory.getLogger("neop2p")
        NeoLog.sink = { level, tag, message, throwable ->
            when (level) {
                NeoLog.Level.INFO -> log.info("[{}] {}", tag, message)
                NeoLog.Level.WARN ->
                    if (throwable != null) log.warn("[{}] {}", tag, message, throwable)
                    else log.warn("[{}] {}", tag, message)
            }
        }
    }
}
