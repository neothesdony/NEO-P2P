package com.neop2p.data.tor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrcBuilderTest {
    @Test fun containsClientOnlyAndAvoidDiskWrites() {
        val torrc = TorrcBuilder.build()
        assertTrue(torrc.contains("ClientOnly 1"))
        assertTrue(torrc.contains("AvoidDiskWrites 1"))
    }

    @Test fun doesNotSetPortsControlOrBridges() {
        val torrc = TorrcBuilder.build()
        assertFalse(torrc.contains("SocksPort"))
        assertFalse(torrc.contains("ControlPort"))
        assertFalse(torrc.contains("HTTPTunnelPort"))
        assertFalse(torrc.contains("Bridge"))
        assertFalse(torrc.contains("UseBridges"))
    }
}
