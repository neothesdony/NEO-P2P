package com.neop2p.data.p2p.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P7.1 — partial merge on mirror ingress. A remote event carrying blank/absent
 * fields must never clobber a locally-owned value.
 */
class RemoteStatusMergeTest {

    @Test
    fun `blank remote does not clobber a local value`() {
        assertEquals("local-txid", mergeRemoteField("", "local-txid"))
        assertEquals("local-txid", mergeRemoteField(null, "local-txid"))
        assertEquals("local-txid", mergeRemoteField("   ", "local-txid"))
    }

    @Test
    fun `non-blank remote overrides the local value`() {
        assertEquals("remote-txid", mergeRemoteField("remote-txid", "local-txid"))
        assertEquals("remote-txid", mergeRemoteField("remote-txid", null))
    }

    @Test
    fun `both blank stays blank`() {
        assertNull(mergeRemoteField(null, null))
        assertNull(mergeRemoteField("", null))
    }

    @Test
    fun `a locally-owned field survives a remote push`() {
        // The creator's funding address is owner-guarded; the buyer's mirror
        // adopts a non-blank remote but keeps its own on a blank echo.
        val creatorAddress = "tb1qseller"
        assertEquals(creatorAddress, mergeRemoteField(null, creatorAddress))
        assertEquals("tb1qbuyer", mergeRemoteField("tb1qbuyer", creatorAddress))
    }

    @Test
    fun `blank remote status never advances a row`() {
        // applyRemoteStatus is the transition gate; an unknown/blank status is
        // dropped before any merge is attempted.
        assertNull(EscrowRouter.applyRemoteStatus("FUNDED", "", false))
        assertNull(EscrowRouter.applyRemoteStatus("FUNDED", "NOT_A_STATUS", false))
    }
}
