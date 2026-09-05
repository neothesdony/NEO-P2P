package com.neop2p.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportNodeStoreTest {

    @Test
    fun `json round-trips through parse`() {
        val json = TransportNodeStore.toJson(
            listOf(
                TransportNode("node-a.example.com", 42420),
                TransportNode("node-b.example.org", 43001)
            )
        )
        val parsed = TransportNodeStore.parse(json)
        assertEquals(2, parsed.size)
        assertEquals("node-a.example.com", parsed[0].host)
        assertEquals(42420, parsed[0].port)
        assertEquals("node-b.example.org", parsed[1].host)
        assertEquals(43001, parsed[1].port)
    }

    @Test
    fun `empty and garbage json yield empty list`() {
        assertTrue(TransportNodeStore.parse("").isEmpty())
        assertTrue(TransportNodeStore.parse("[not json").isEmpty())
        assertTrue(TransportNodeStore.parse("[]").isEmpty())
    }

    @Test
    fun `invalid entries are dropped`() {
        val parsed = TransportNodeStore.parse(
            """[
                {"host":"  ", "port":42420},
                {"host":"bad-port.example.com", "port":0},
                {"host":"bad-port2.example.com", "port":70000},
                {"host":"Good.example.com", "port":42420}
            ]"""
        )
        assertEquals(1, parsed.size)
        assertEquals("good.example.com", parsed[0].host)
    }

    @Test
    fun `hosts are normalized and duplicates deduped`() {
        val json = TransportNodeStore.toJson(
            listOf(
                TransportNode("UPPER.Example.COM", 42420),
                TransportNode("upper.example.com", 42420)
            )
        )
        val parsed = TransportNodeStore.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("upper.example.com", parsed[0].host)
    }

    @Test
    fun `write path matches read path`() {
        // The store needs an Android Context, so plain JUnit exercises the
        // write path through the same serialization add() uses: toJson is
        // exactly what add() persists, parse is exactly what all() reads.
        val json = TransportNodeStore.toJson(listOf(TransportNode("node.example.com", 42420)))
        val parsed = TransportNodeStore.parse(json)
        assertEquals("node.example.com", parsed[0].host)
        assertEquals(42420, parsed[0].port)
    }
}
