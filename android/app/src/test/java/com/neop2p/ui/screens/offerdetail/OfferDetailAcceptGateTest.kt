package com.neop2p.ui.screens.offerdetail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferDetailAcceptGateTest {

    @Test
    fun `buyer with valid address can accept`() {
        assertTrue(acceptEnabled(accepting = false, iAmBuyer = true, addressValid = true))
    }

    @Test
    fun `buyer with invalid address cannot accept`() {
        assertFalse(acceptEnabled(accepting = false, iAmBuyer = true, addressValid = false))
    }

    @Test
    fun `accepting blocks the button even with valid address`() {
        assertFalse(acceptEnabled(accepting = true, iAmBuyer = true, addressValid = true))
    }

    @Test
    fun `non-buyer ignores address`() {
        assertTrue(acceptEnabled(accepting = false, iAmBuyer = false, addressValid = false))
    }

    @Test
    fun `non-buyer blocked while accepting`() {
        assertFalse(acceptEnabled(accepting = true, iAmBuyer = false, addressValid = false))
    }
}
