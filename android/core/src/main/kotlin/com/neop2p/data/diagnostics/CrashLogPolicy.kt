package com.neop2p.data.diagnostics

/**
 * Privacy policy for the local crash log (M-10, 2026-11-03).
 *
 * A crash trace routinely contains a peerId, a Bitcoin address, or a session
 * identifier. None of that leaves the device, but the log is shareable from
 * Settings, so it is redacted before it is written.
 */
object CrashLogPolicy {
    const val MAX_FILES = 5

    private val PEER_ID = Regex("""12D3KooW\w+""")
    private val BECH32_MAIN = Regex("""bc1[0-9a-z]+""")
    private val BECH32_TEST = Regex("""tb1[0-9a-z]+""")
    private val BASE58_ADDRESS = Regex("""[13][a-km-zA-HJ-NP-Z1-9]{25,34}""")

    fun redact(text: String): String = text
        .replace(PEER_ID, "<redacted>")
        .replace(BECH32_MAIN, "<redacted>")
        .replace(BECH32_TEST, "<redacted>")
        .replace(BASE58_ADDRESS, "<redacted>")

    /** True when writing one more file would exceed [MAX_FILES]. */
    fun shouldRotate(existingCount: Int): Boolean = existingCount >= MAX_FILES
}
