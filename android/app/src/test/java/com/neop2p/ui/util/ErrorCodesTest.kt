package com.neop2p.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorCodesTest {

    @Test
    fun `insufficient balance maps to stable code`() {
        assertEquals(
            ErrorCodes.ERR_INSUFFICIENT_BALANCE,
            ErrorCodes.codeFor("Insufficient balance: have 100sats, need 200sats")
        )
    }

    @Test
    fun `broadcast and release failures map to broadcast code`() {
        assertEquals(
            ErrorCodes.ERR_BROADCAST,
            ErrorCodes.codeFor("Broadcast failed: reject-mempool-...")
        )
        assertEquals(
            ErrorCodes.ERR_BROADCAST,
            ErrorCodes.codeFor("Release failed: Broadcast failed: ...")
        )
    }

    @Test
    fun `funding gate failure maps to funding code`() {
        assertEquals(
            ErrorCodes.ERR_FUNDING_TIMEOUT,
            ErrorCodes.codeFor("Funding not confirmed: tx not found")
        )
    }

    @Test
    fun `invalid destination maps to address code`() {
        assertEquals(
            ErrorCodes.ERR_INVALID_ADDRESS,
            ErrorCodes.codeFor("Invalid destination address: abc")
        )
    }

    @Test
    fun `unknown or blank messages render no code`() {
        assertNull(ErrorCodes.codeFor("Something unexpected happened"))
        assertNull(ErrorCodes.codeFor(null))
        assertNull(ErrorCodes.codeFor(""))
    }
}
