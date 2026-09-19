package com.neop2p.data.escrow

/**
 * Pure gate for payout destinations (2026-09-07). A payout must never
 * send the buyer's sats to the platform fee wallet or back into the
 * escrow's own multisig — both happened in the 2026-09-07 flow test
 * (fee-wallet address pasted at accept; multisig fallback when the
 * buyer's address never arrived). Case-insensitive compare.
 */
object PayoutAddressGate {
    fun isForbidden(address: String, feeWalletAddress: String, fundingAddress: String?): Boolean {
        val a = address.trim()
        if (a.isBlank()) return true
        if (a.equals(feeWalletAddress, ignoreCase = true)) return true
        if (fundingAddress?.isNotBlank() == true && a.equals(fundingAddress, ignoreCase = true)) return true
        return false
    }
}
