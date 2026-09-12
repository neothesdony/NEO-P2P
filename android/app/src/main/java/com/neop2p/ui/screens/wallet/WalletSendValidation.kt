package com.neop2p.ui.screens.wallet

import com.neop2p.R
import com.neop2p.ui.util.ErrorCodes

/** Send-form validation outcomes, rendered by WalletScreen. */
enum class WalletInputError(val messageRes: Int, val code: String?) {
    WRONG_NETWORK(R.string.wallet_error_wrong_network, ErrorCodes.ERR_WRONG_NETWORK),
    INVALID_ADDRESS(R.string.wallet_error_invalid_address, ErrorCodes.ERR_INVALID_ADDRESS),
    INVALID_AMOUNT(R.string.wallet_invalid_amount, null),
    DUST(R.string.wallet_error_dust, ErrorCodes.ERR_DUST),
    INSUFFICIENT_BALANCE(R.string.wallet_error_insufficient_balance, ErrorCodes.ERR_INSUFFICIENT_BALANCE)
}

/** Dust floor for a wallet send, matching WalletService.DUST_THRESHOLD_SATS. */
const val WALLET_SEND_DUST_SATS: Long = 546L

/**
 * Audit P3-7 (2026-09-12): validation must use the SPENDABLE (confirmed)
 * balance, because ChainMonitor.getAddressUtxos only returns confirmed UTXOs.
 * [amountSats] is null while the field is blank.
 */
fun sendAmountError(amountSats: Long?, spendableSats: Long): WalletInputError? = when {
    amountSats == null -> null
    amountSats <= 0L -> WalletInputError.INVALID_AMOUNT
    amountSats < WALLET_SEND_DUST_SATS -> WalletInputError.DUST
    amountSats > spendableSats -> WalletInputError.INSUFFICIENT_BALANCE
    else -> null
}
