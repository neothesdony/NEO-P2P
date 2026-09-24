package com.neop2p.data.p2p.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatIngestGateTest {
    @Test fun `creator and matched peer may chat`() {
        assertTrue(ChatIngestGate.mayIngest("seller", "seller", "buyer"))
        assertTrue(ChatIngestGate.mayIngest("buyer", "seller", "buyer"))
    }
    @Test fun `third party is rejected`() {
        assertFalse(ChatIngestGate.mayIngest("stranger", "seller", "buyer"))
    }
    @Test fun `blank or null parties fail closed`() {
        assertFalse(ChatIngestGate.mayIngest("", "seller", "buyer"))
        assertFalse(ChatIngestGate.mayIngest("stranger", null, null))
        assertTrue(ChatIngestGate.mayIngest("seller", "seller", null))
    }
}
