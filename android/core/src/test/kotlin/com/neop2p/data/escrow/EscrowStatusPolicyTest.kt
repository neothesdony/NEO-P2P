package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal set is load-bearing twice: the escrow sync publisher treats a
 * terminal status as "broadcast once, no resume re-publish", and the chat
 * composer treats it as "trade closed, read-only". Both must agree, so the
 * set lives in one place.
 */
class EscrowStatusPolicyTest {

    @Test
    fun `released refunded and cancelled are terminal`() {
        assertTrue(EscrowStatusPolicy.isTerminal("RELEASED"))
        assertTrue(EscrowStatusPolicy.isTerminal("REFUNDED"))
        assertTrue(EscrowStatusPolicy.isTerminal("CANCELLED"))
    }

    @Test
    fun `every live lifecycle status is not terminal`() {
        val live = listOf(
            "FUNDING", "FUNDED", "SIGNED", "PAYMENT_PENDING",
            "RECEIPT_SENT", "CONFIRMING", "DISPUTED", "RESOLVING"
        )
        live.forEach { assertFalse("$it must stay non-terminal", EscrowStatusPolicy.isTerminal(it)) }
    }

    @Test
    fun `null and blank are not terminal`() {
        assertFalse(EscrowStatusPolicy.isTerminal(null))
        assertFalse(EscrowStatusPolicy.isTerminal(""))
    }

    @Test
    fun `matching is exact so a lowercased status cannot slip through`() {
        assertFalse(EscrowStatusPolicy.isTerminal("released"))
        assertFalse(EscrowStatusPolicy.isTerminal("Released"))
    }

    @Test
    fun `terminal set holds exactly the three end states`() {
        assertEquals(setOf("RELEASED", "REFUNDED", "CANCELLED"), EscrowStatusPolicy.TERMINAL)
    }
}
