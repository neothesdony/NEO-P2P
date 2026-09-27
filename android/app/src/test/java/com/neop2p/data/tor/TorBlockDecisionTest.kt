package com.neop2p.data.tor

import com.neop2p.data.network.TorState
import com.neop2p.ui.util.TorBlockDecision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBlockDecisionTest {
    @Test fun offersOverrideOnlyWhenEnabledAndNotConnected() {
        assertFalse(TorBlockDecision.shouldOfferOverride(false, TorState.Disabled))
        assertTrue(TorBlockDecision.shouldOfferOverride(true, TorState.Starting))
        assertTrue(TorBlockDecision.shouldOfferOverride(true, TorState.Bootstrapping(10)))
        assertTrue(TorBlockDecision.shouldOfferOverride(true, TorState.Failed("x")))
        assertFalse(TorBlockDecision.shouldOfferOverride(true, TorState.Connected(8118)))
    }
}
