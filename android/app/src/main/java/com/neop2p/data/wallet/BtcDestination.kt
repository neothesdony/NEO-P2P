package com.neop2p.data.wallet

import org.bitcoinj.base.Address
import org.bitcoinj.base.SegwitAddress
import org.bitcoinj.core.NetworkParameters

/**
 * P3.1 destination validation. Single source of truth for "can this address
 * receive a wallet/escrow spend?" — used by the wallet send path AND the
 * offer-detail payout gate so they can never drift.
 */
enum class BtcAddressError {
    /** Valid on the other network. */
    WRONG_NETWORK,

    /** Not parseable as an address. */
    INVALID,

    /**
     * Witness v1 (P2TR / `bc1p…` / `tb1p…`). Explicitly unsupported: a Taproot
     * output is 43 vB (vs 34 for P2PKH), so the escrow payout fee model would
     * underpay, and NEO-P2P has no tapscript policy. A future witness version
     * (v2+) is rejected as [INVALID].
     */
    TAPROOT_UNSUPPORTED
}

/**
 * null when [address] is spendable on [params]; otherwise the reason. Blank is
 * "not typed yet" (null) so a form does not flash an error.
 */
fun btcAddressError(
    address: String,
    params: NetworkParameters,
    otherParams: NetworkParameters
): BtcAddressError? {
    val trimmed = address.trim()
    if (trimmed.isEmpty()) return null
    val parsed = try {
        Address.fromString(params, trimmed)
    } catch (e: Exception) {
        return try {
            Address.fromString(otherParams, trimmed)
            BtcAddressError.WRONG_NETWORK
        } catch (_: Exception) {
            BtcAddressError.INVALID
        }
    }
    if (parsed is SegwitAddress) {
        if (parsed.witnessVersion == 1) return BtcAddressError.TAPROOT_UNSUPPORTED
        if (parsed.witnessVersion > 1) return BtcAddressError.INVALID
    }
    return null
}
