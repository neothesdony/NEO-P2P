package com.neop2p.data.p2p.routing

import com.neop2p.domain.model.EscrowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P7.2 — boundary status normalization. The allow-list for TRANSITIONS is
 * fail-closed: an unknown string is dropped (never throws, never advances a
 * row), while forward-compat (persisting the raw string for diagnostics) is
 * deliberately NOT done here. Do not relax `ALLOWED_REMOTE`.
 */
class EscrowStatusIngestTest {

    @Test
    fun `every known status is recognized for a forward move`() {
        // Walk the forward happy path; each step must be accepted.
        assertEquals("FUNDED", EscrowRouter.applyRemoteStatus("FUNDING", "FUNDED", false))
        assertEquals("SIGNED", EscrowRouter.applyRemoteStatus("FUNDED", "SIGNED", false))
        assertEquals("PAYMENT_PENDING", EscrowRouter.applyRemoteStatus("SIGNED", "PAYMENT_PENDING", false))
        assertEquals("RECEIPT_SENT", EscrowRouter.applyRemoteStatus("PAYMENT_PENDING", "RECEIPT_SENT", false))
        assertEquals("CONFIRMING", EscrowRouter.applyRemoteStatus("RECEIPT_SENT", "CONFIRMING", false))
        assertEquals("RELEASED", EscrowRouter.applyRemoteStatus("CONFIRMING", "RELEASED", false))
        assertEquals("DISPUTED", EscrowRouter.applyRemoteStatus("FUNDED", "DISPUTED", false))
        assertEquals("REFUNDED", EscrowRouter.applyRemoteStatus("DISPUTED", "REFUNDED", false))
        assertEquals("CANCELLED", EscrowRouter.applyRemoteStatus("FUNDING", "CANCELLED", false))
    }

    @Test
    fun `every enum name is either accepted or explicitly dropped, never throws`() {
        for (status in EscrowStatus.entries) {
            // Must not throw for any known name from any local state.
            EscrowRouter.applyRemoteStatus("FUNDING", status.name, false)
            EscrowRouter.applyRemoteStatus(null, status.name, false)
            EscrowRouter.applyRemoteStatus("DISPUTED", status.name, false)
        }
    }

    @Test
    fun `unknown raw values are dropped and never advance`() {
        assertNull(EscrowRouter.applyRemoteStatus("FUNDING", "funded", false))   // case-sensitive
        assertNull(EscrowRouter.applyRemoteStatus("FUNDING", "PAID", false))
        assertNull(EscrowRouter.applyRemoteStatus("FUNDING", "CONFIRMED", false))
        assertNull(EscrowRouter.applyRemoteStatus("FUNDING", "unknown-status", false))
        assertNull(EscrowRouter.applyRemoteStatus("FUNDING", "", false))
        assertNull(EscrowRouter.applyRemoteStatus("FUNDING", "  ", false))
    }

    @Test
    fun `unknown value with no local row never creates one`() {
        assertNull(EscrowRouter.applyRemoteStatus(null, "PAID", false))
        assertNull(EscrowRouter.applyRemoteStatus(null, "unknown-status", true))
    }

    @Test
    fun `creator row refuses a remote cancel`() {
        assertNull(EscrowRouter.applyRemoteStatus("FUNDED", "CANCELLED", localIsCreator = true))
        // The mirror still converges on the creator's cancel.
        assertEquals("CANCELLED", EscrowRouter.applyRemoteStatus("FUNDED", "CANCELLED", localIsCreator = false))
    }
}
