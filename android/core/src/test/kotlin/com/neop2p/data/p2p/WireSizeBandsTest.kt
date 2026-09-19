package com.neop2p.data.p2p

import org.junit.Assert.assertTrue
import org.junit.Test

class WireSizeBandsTest {
    @Test
    fun `opportunistic band sits under the link-packet band`() {
        // ENCRYPTED_PACKET_MAX_CONTENT < LINK_PACKET_MAX_CONTENT — content
        // above the opportunistic ceiling must escalate to a link packet,
        // and above the link ceiling to a Resource.
        assertTrue(WireSizeBands.OPPORTUNISTIC_MAX_BYTES < WireSizeBands.LINK_PACKET_MAX_BYTES)
    }

    @Test
    fun `inbound limit is above the largest single-packet band`() {
        // 128 KB inbound cap still admits full Resource transfers (images),
        // which start at > 319 B.
        assertTrue(WireSizeBands.MAX_INBOUND_KB * 1024 > WireSizeBands.LINK_PACKET_MAX_BYTES)
    }

    @Test
    fun `inbound limit is explicit and bounded`() {
        assertTrue(WireSizeBands.MAX_INBOUND_KB in 1..512)
    }
}
