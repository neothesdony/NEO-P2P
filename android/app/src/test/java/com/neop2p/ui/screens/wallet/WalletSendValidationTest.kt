package com.neop2p.ui.screens.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests for send-amount validation (audit P3-7, 2026-09-12).
 *
 * The screen used to validate against confirmed + unconfirmed, while the
 * service can only spend confirmed UTXOs — a send sized on unconfirmed funds
 * passed every UI gate and then failed at broadcast with a raw error.
 */
class WalletSendValidationTest {

    @Test
    fun `blank input is not an error yet`() {
        assertNull(sendAmountError(amountSats = null, spendableSats = 100_000L))
    }

    @Test
    fun `zero and negative are invalid amounts`() {
        assertEquals(WalletInputError.INVALID_AMOUNT, sendAmountError(0L, 100_000L))
        assertEquals(WalletInputError.INVALID_AMOUNT, sendAmountError(-5L, 100_000L))
    }

    @Test
    fun `below the dust threshold is a dust error`() {
        assertEquals(WalletInputError.DUST, sendAmountError(500L, 100_000L))
    }

    @Test
    fun `exactly the dust threshold is allowed`() {
        assertNull(sendAmountError(546L, 100_000L))
    }

    @Test
    fun `above spendable balance is insufficient`() {
        assertEquals(WalletInputError.INSUFFICIENT_BALANCE, sendAmountError(100_001L, 100_000L))
    }

    @Test
    fun `equal to spendable balance passes (fee may still reject it later)`() {
        assertNull(sendAmountError(100_000L, 100_000L))
    }
}
