package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PropagationNodeTest {
    private val nodes = listOf(
        PropagationNodeInfo("aaa", isActive = true, hops = 4),
        PropagationNodeInfo("bbb", isActive = true, hops = 1),
        PropagationNodeInfo("ccc", isActive = false, hops = 0),
    )

    @Test
    fun `picks the fewest-hops active node`() {
        assertEquals("bbb", PropagationNodeSelector.best(nodes))
    }

    @Test
    fun `ignores inactive nodes`() {
        assertEquals("aaa", PropagationNodeSelector.best(nodes.filter { it.destHashHex != "bbb" }))
    }

    @Test
    fun `no active nodes yields null`() {
        assertNull(PropagationNodeSelector.best(listOf(PropagationNodeInfo("ccc", isActive = false, hops = 0))))
    }

    @Test
    fun `a far first node loses to a nearer second`() {
        val candidates = listOf(
            PropagationNodeInfo("far", isActive = true, hops = 9),
            PropagationNodeInfo("near", isActive = true, hops = 2),
        )
        assertEquals("near", PropagationNodeSelector.best(candidates))
    }
}
