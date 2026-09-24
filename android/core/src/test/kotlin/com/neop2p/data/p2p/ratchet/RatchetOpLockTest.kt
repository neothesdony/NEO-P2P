package com.neop2p.data.p2p.ratchet

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RatchetOpLockTest {
    @Test fun `concurrent operations are serialized`() = runBlocking {
        val lock = RatchetOpLock()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        (1..50).map {
            async {
                lock.withPeer {
                    val now = active.incrementAndGet()
                    maxActive.updateAndGet { m -> maxOf(m, now) }
                    Thread.sleep(1)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, maxActive.get())
    }
}
