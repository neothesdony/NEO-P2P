package com.neop2p.data.p2p.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorMergeTest {

    @Test
    fun `creator keeps its own value and the mirror adopts the creator's`() {
        // On the creator (seller) row a remote echo must not overwrite.
        assertEquals("mine", mergeCreatorOwned("theirs", "mine", localIsCreator = true))
        // On the mirror (buyer) row the creator's value is authoritative.
        assertEquals("theirs", mergeCreatorOwned("theirs", "mine", localIsCreator = false))
        // A blank remote never clobbers either side.
        assertEquals("mine", mergeCreatorOwned(null, "mine", localIsCreator = false))
    }

    @Test
    fun `mirror-owned field survives on the buyer and is adopted by the seller`() {
        // On the buyer's row, the seller's remote push must not overwrite.
        assertEquals("buyer-addr", mergeMirrorOwned("attacker-addr", "buyer-addr", localIsCreator = false))
        // On the seller's row the buyer's value is authoritative.
        assertEquals("buyer-addr", mergeMirrorOwned("buyer-addr", "stale", localIsCreator = true))
        // Null local on the buyer's row falls back to blank, not the remote.
        assertNull(mergeMirrorOwned("attacker-addr", null, localIsCreator = false))
    }

    @Test
    fun `mergeOnce adopts only when the local value is blank`() {
        assertEquals("remote-script", mergeOnce("remote-script", null))
        assertEquals("remote-script", mergeOnce("remote-script", "  "))
        assertEquals("local-script", mergeOnce("remote-script", "local-script"))
        assertEquals("local-script", mergeOnce(null, "local-script"))
    }
}
