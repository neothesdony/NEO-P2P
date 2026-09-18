package com.neop2p.data.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2.3 — anti-fee-sniping locktime. A failing tip fetch must yield locktime 0,
 * never a stale height (a stale height could make the tx non-final forever).
 */
class WalletSendLocktimeTest {

    @Test
    fun `known tip becomes the locktime`() {
        assertEquals(800_000L, WalletService.lockTimeFor(800_000L))
    }

    @Test
    fun `unknown tip yields zero`() {
        assertEquals(0L, WalletService.lockTimeFor(null))
    }

    @Test
    fun `zero and negative tips yield zero`() {
        assertEquals(0L, WalletService.lockTimeFor(0L))
        assertEquals(0L, WalletService.lockTimeFor(-1L))
    }
}
