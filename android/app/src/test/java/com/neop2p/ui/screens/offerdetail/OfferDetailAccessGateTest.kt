package com.neop2p.ui.screens.offerdetail

import com.neop2p.domain.model.OfferStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locked-offer access gate (2026-09-06): a locked offer's details are
 * private to the trade — only the creator (seller), the matched peer
 * (buyer), or the arbitrator (admin) may view them. Pure-function tests
 * mirroring canViewOfferDetail (the home feed enforces the same rule at
 * the tap site; this is the load-time gate).
 */
class OfferDetailAccessGateTest {

    @Test
    fun `open offers are viewable by anyone`() {
        assertTrue(canViewOfferDetail(OfferStatus.OPEN, "creator", null, "stranger", isArbitrator = false))
        assertTrue(canViewOfferDetail(OfferStatus.OPEN, "creator", "buyer", "", isArbitrator = false))
    }

    @Test
    fun `creator can open own locked offer`() {
        assertTrue(canViewOfferDetail(OfferStatus.MATCHED, "seller", "buyer", "seller", isArbitrator = false))
        assertTrue(canViewOfferDetail(OfferStatus.ESCROWED, "seller", "buyer", "seller", isArbitrator = false))
        assertTrue(canViewOfferDetail(OfferStatus.COMPLETED, "seller", "buyer", "seller", isArbitrator = false))
    }

    @Test
    fun `matched peer can open locked offer`() {
        assertTrue(canViewOfferDetail(OfferStatus.MATCHED, "seller", "buyer", "buyer", isArbitrator = false))
    }

    @Test
    fun `arbitrator can open any locked offer`() {
        assertTrue(canViewOfferDetail(OfferStatus.ESCROWED, "seller", "buyer", "", isArbitrator = true))
        assertTrue(canViewOfferDetail(OfferStatus.CANCELLED, "seller", "buyer", "anyone", isArbitrator = true))
    }

    @Test
    fun `third party cannot open locked offer`() {
        assertFalse(canViewOfferDetail(OfferStatus.MATCHED, "seller", "buyer", "stranger", isArbitrator = false))
        assertFalse(canViewOfferDetail(OfferStatus.PAUSED, "seller", null, "stranger", isArbitrator = false))
        assertFalse(canViewOfferDetail(OfferStatus.ESCROWED, "seller", "buyer", "stranger", isArbitrator = false))
    }

    @Test
    fun `blank myPeerId cannot open locked offer unless arbitrator`() {
        assertFalse(canViewOfferDetail(OfferStatus.MATCHED, "seller", "buyer", "", isArbitrator = false))
    }

    @Test
    fun `peer ids match case-insensitively`() {
        assertTrue(canViewOfferDetail(OfferStatus.MATCHED, "SELLER", "buyer", "seller", isArbitrator = false))
    }
}
