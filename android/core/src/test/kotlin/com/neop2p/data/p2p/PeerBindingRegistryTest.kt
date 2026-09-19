package com.neop2p.data.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerBindingRegistryTest {
    @Test fun `record then verify`() {
        val r = PeerBindingRegistry()
        assertNull(r.record("p1", "dest1"))
        assertTrue(r.isVerified("p1", "dest1"))
        assertFalse(r.isVerified("p1", "dest2"))
        assertFalse(r.isVerified("p2", "dest1"))
    }

    @Test fun `rebind returns previous dest`() {
        val r = PeerBindingRegistry()
        r.record("p1", "dest1")
        assertTrue(r.record("p1", "dest2") == "dest1")
        assertTrue(r.isVerified("p1", "dest2"))
        assertTrue(r.verifiedDest("p1") == "dest2")
    }
}
