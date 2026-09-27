package com.neop2p.data.p2p.routing

import com.neop2p.domain.model.OfferStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowMirrorRowGateTest {

    /**
     * The live SELL path: the buyer TAKES the offer, so on the buyer's device
     * `matched_peer_id` is the buyer itself (set by `claimOffer`) and the
     * sender (the creator/seller) must be recognized as the counterparty.
     * The original gate compared `matched_peer_id == senderPeerId`, which
     * dropped this legitimate mirror row entirely.
     */
    @Test fun `the buyer accepts the seller's escrow for an offer it matched`() {
        assertTrue(EscrowRouter.canCreateMirrorRow("seller", "buyer", OfferStatus.MATCHED.name, "seller", "buyer"))
        assertTrue(EscrowRouter.canCreateMirrorRow("seller", "buyer", OfferStatus.ESCROWED.name, "seller", "buyer"))
        assertTrue(EscrowRouter.canCreateMirrorRow("seller", "buyer", OfferStatus.COMPLETED.name, "seller", "buyer"))
    }

    @Test fun `the creator accepts the matched buyer as a late join`() {
        assertTrue(EscrowRouter.canCreateMirrorRow("seller", "buyer", OfferStatus.ESCROWED.name, "buyer", "seller"))
    }

    @Test fun `an unmatched or unknown offer is refused`() {
        // A stranger claiming to be the counterparty.
        assertFalse(EscrowRouter.canCreateMirrorRow("seller", "buyer", OfferStatus.MATCHED.name, "stranger", "buyer"))
        // The local device is neither party.
        assertFalse(EscrowRouter.canCreateMirrorRow("seller", "buyer", OfferStatus.MATCHED.name, "seller", "third"))
        // Not locked.
        assertFalse(EscrowRouter.canCreateMirrorRow("seller", "buyer", OfferStatus.OPEN.name, "seller", "buyer"))
        // Missing fields.
        assertFalse(EscrowRouter.canCreateMirrorRow("seller", null, OfferStatus.MATCHED.name, "seller", "buyer"))
        assertFalse(EscrowRouter.canCreateMirrorRow(null, "buyer", OfferStatus.MATCHED.name, "seller", "buyer"))
        assertFalse(EscrowRouter.canCreateMirrorRow("seller", "buyer", null, "seller", "buyer"))
        assertFalse(EscrowRouter.canCreateMirrorRow("seller", "buyer", OfferStatus.MATCHED.name, "", "buyer"))
        assertFalse(EscrowRouter.canCreateMirrorRow("seller", "buyer", OfferStatus.MATCHED.name, "seller", ""))
    }
}
