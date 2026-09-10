package com.neop2p.ui.util

/**
 * Stable, reportable machine codes for money-path failures.
 *
 * NEO-P2P has no support desk — when a user hits a failure they can only
 * report it in words. These codes give them (and us) a stable token:
 * "Kode: ERR_INSUFFICIENT_BALANCE" means the same thing on every build.
 *
 * Codes are derived from the failure message at the UI boundary (single
 * mapping table, no throw-site churn). Unknown messages render no code.
 */
object ErrorCodes {
    const val ERR_FUNDING_TIMEOUT = "ERR_FUNDING_TIMEOUT"
    const val ERR_BROADCAST = "ERR_BROADCAST"
    const val ERR_FEE_ESTIMATE = "ERR_FEE_ESTIMATE" // reserved: fee API falls back to defaults today
    const val ERR_INSUFFICIENT_BALANCE = "ERR_INSUFFICIENT_BALANCE"
    const val ERR_DUST = "ERR_DUST" // reserved: change-dust is absorbed into the fee today
    const val ERR_INVALID_QR = "ERR_INVALID_QR"
    const val ERR_INVALID_SEED = "ERR_INVALID_SEED"
    const val ERR_INVALID_ADDRESS = "ERR_INVALID_ADDRESS"
    const val ERR_WRONG_NETWORK = "ERR_WRONG_NETWORK"
    const val ERR_CAMERA_DENIED = "ERR_CAMERA_DENIED"
    const val ERR_STORAGE_FULL = "ERR_STORAGE_FULL"
    const val ERR_AMOUNT_MISMATCH = "ERR_AMOUNT_MISMATCH"

    /** Map a failure message to its stable code, or null when unmapped. */
    fun codeFor(message: String?): String? {
        if (message.isNullOrBlank()) return null
        val m = message.lowercase()
        return when {
            m.contains("insufficient balance") -> ERR_INSUFFICIENT_BALANCE
            m.contains("invalid destination address") -> ERR_INVALID_ADDRESS
            m.contains("wrong network") -> ERR_WRONG_NETWORK
            m.contains("dust") -> ERR_DUST
            m.contains("broadcast failed") || m.contains("release failed") -> ERR_BROADCAST
            m.contains("funding not confirmed") -> ERR_FUNDING_TIMEOUT
            m.contains("camera") && m.contains("denied") -> ERR_CAMERA_DENIED
            m.contains("storage full") || m.contains("disk full") || m.contains("database full") -> ERR_STORAGE_FULL
            m.contains("amount mismatch") -> ERR_AMOUNT_MISMATCH
            else -> null
        }
    }
}
