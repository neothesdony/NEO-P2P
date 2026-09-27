package com.neop2p.data.tor

/**
 * Extra lines for the main torrc (`TorService.getTorrc`). Ports and the control
 * socket are owned by TorService; this file is the single insertion point for a
 * future `Bridge` / `UseBridges` line.
 */
object TorrcBuilder {
    fun build(): String = buildString {
        appendLine("ClientOnly 1")
        appendLine("AvoidDiskWrites 1")
    }
}
