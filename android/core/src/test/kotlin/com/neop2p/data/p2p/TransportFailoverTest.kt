package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportFailoverTest {
    private val primary = "relay1.custom-minipc.com" to 42420
    private val community = listOf("rns.beleth.net" to 4242, "rns.jaykayenn.net" to 4242)

    @Test fun `primary up means community nodes stay disconnected`() {
        assertEquals(listOf(primary), TransportFailover.desiredNodes(primary, community, primaryOnline = true))
    }

    @Test fun `primary down means community nodes connect`() {
        assertEquals(listOf(primary) + community, TransportFailover.desiredNodes(primary, community, primaryOnline = false))
    }

    @Test fun `primary recovers and community nodes detach`() {
        assertEquals(listOf(primary), TransportFailover.desiredNodes(primary, community, primaryOnline = true))
    }
}
