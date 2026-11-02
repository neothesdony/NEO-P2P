package com.neop2p.data.p2p.ratchet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingInitHeadersTest {

    @Test
    fun `headers are scoped per peer`() {
        val m = PendingInitHeaders()
        m.put("a", byteArrayOf(1)); m.put("b", byteArrayOf(2))
        assertArrayEquals(byteArrayOf(1), m.consume("a"))
        assertArrayEquals(byteArrayOf(2), m.consume("b"))
        assertNull(m.consume("a"))
    }

    @Test
    fun `concurrent puts and consumes stay per-peer`() = runBlocking<Unit> {
        val m = PendingInitHeaders()
        (1..100).map { i ->
            async(Dispatchers.Default) {
                val p = "p$i"; val h = byteArrayOf(i.toByte())
                m.put(p, h)
                assertArrayEquals(h, m.consume(p))
            }
        }.awaitAll()
    }
}
