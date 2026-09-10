package com.neop2p.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferStatusGateTest {

    @Test
    fun `open and paused offers are editable`() {
        assertTrue(isOfferEditable(OfferStatus.OPEN))
        assertTrue(isOfferEditable(OfferStatus.PAUSED))
    }

    @Test
    fun `locked and terminal offers are not editable`() {
        assertFalse(isOfferEditable(OfferStatus.MATCHED))
        assertFalse(isOfferEditable(OfferStatus.ESCROWED))
        assertFalse(isOfferEditable(OfferStatus.COMPLETED))
        assertFalse(isOfferEditable(OfferStatus.DISPUTED))
        assertFalse(isOfferEditable(OfferStatus.CANCELLED))
    }
}
