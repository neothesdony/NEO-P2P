package com.neop2p.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorGateTest {
    @Test fun disabledAlwaysAllowsDirect() {
        assertEquals(TorVerdict.ALLOW_DIRECT, TorGate.verdict(false, TorState.Connected(8118), false))
        assertEquals(TorVerdict.ALLOW_DIRECT, TorGate.verdict(false, TorState.Starting, false))
    }

    @Test fun enabledAndConnectedRoutesTor() {
        assertEquals(TorVerdict.ALLOW_TOR, TorGate.verdict(true, TorState.Connected(8118), false))
        // override is irrelevant once connected
        assertEquals(TorVerdict.ALLOW_TOR, TorGate.verdict(true, TorState.Connected(8118), true))
    }

    @Test fun enabledAndNotConnectedBlocks() {
        assertEquals(TorVerdict.BLOCK, TorGate.verdict(true, TorState.Starting, false))
        assertEquals(TorVerdict.BLOCK, TorGate.verdict(true, TorState.Bootstrapping(40), false))
        assertEquals(TorVerdict.BLOCK, TorGate.verdict(true, TorState.Failed("x"), false))
    }

    @Test fun overrideAllowsDirectWhenEnabledAndNotConnected() {
        assertEquals(TorVerdict.ALLOW_DIRECT, TorGate.verdict(true, TorState.Starting, true))
        assertEquals(TorVerdict.ALLOW_DIRECT, TorGate.verdict(true, TorState.Failed("x"), true))
    }

    @Test fun blockReasonOnlyForBlockingStates() {
        assertEquals("tor_starting", TorGate.blockReason(TorState.Starting))
        assertEquals("tor_bootstrapping", TorGate.blockReason(TorState.Bootstrapping(40)))
        assertEquals("tor_failed", TorGate.blockReason(TorState.Failed("x")))
        assertNull(TorGate.blockReason(TorState.Connected(8118)))
        assertNull(TorGate.blockReason(TorState.Disabled))
    }
}
