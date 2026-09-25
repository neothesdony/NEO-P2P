package com.neop2p.data.tor

import com.neop2p.data.network.TorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorStatusLabelTest {
    @Test fun mapsStatesToKeys() {
        assertNull(TorStatusLabel.of(TorState.Disabled))
        assertEquals("tor_status_starting", TorStatusLabel.of(TorState.Starting))
        assertEquals("tor_status_bootstrapping", TorStatusLabel.of(TorState.Bootstrapping(40)))
        assertEquals("tor_status_connected", TorStatusLabel.of(TorState.Connected(8118)))
        assertEquals("tor_status_failed", TorStatusLabel.of(TorState.Failed("x")))
    }
}
