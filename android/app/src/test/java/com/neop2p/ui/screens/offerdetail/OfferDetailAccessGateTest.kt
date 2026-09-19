package com.neop2p.ui.screens.offerdetail

import com.neop2p.domain.model.OfferStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locked-offer access gate: creator + matched peer only (Phase 3 removed the arbitrator branch). */
class OfferDetailAccessGateTest {

    @Test fun `open offers are viewable by anyone`() {
        assertTrue(canViewOfferDetail(OfferStatus.OPEN, "creator", null, "stranger"))
        assertTrue(canViewOfferDetail(OfferStatus.OPEN, "creator", "buyer", ""))
    }

    @Test fun `creator can open own locked offer`() {
        assertTrue(canViewOfferDetail(OfferStatus.MATCHED, "seller", "buyer", "seller"))
        assertTrue(canViewOfferDetail(OfferStatus.ESCROWED, "seller", "buyer", "seller"))
        assertTrue(canViewOfferDetail(OfferStatus.COMPLETED, "seller", "buyer", "seller"))
    }

    @Test fun `matched peer can open locked offer`() {
        assertTrue(canViewOfferDetail(OfferStatus.MATCHED, "seller", "buyer", "buyer"))
    }

    @Test fun `third party cannot open locked offer`() {
        assertFalse(canViewOfferDetail(OfferStatus.MATCHED, "seller", "buyer", "stranger"))
        assertFalse(canViewOfferDetail(OfferStatus.PAUSED, "seller", null, "stranger"))
        assertFalse(canViewOfferDetail(OfferStatus.ESCROWED, "seller", "buyer", "stranger"))
    }

    @Test fun `blank myPeerId cannot open locked offer`() {
        assertFalse(canViewOfferDetail(OfferStatus.MATCHED, "seller", "buyer", ""))
    }

    @Test fun `peer ids match case-insensitively`() {
        assertTrue(canViewOfferDetail(OfferStatus.MATCHED, "SELLER", "buyer", "seller"))
    }
}
