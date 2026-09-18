package com.neop2p

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class NeoLogTest {

    @After
    fun resetSink() {
        NeoLog.sink = { _, _, _, _ -> }
    }

    @Test
    fun `routes info and warn to the sink`() {
        val seen = mutableListOf<String>()
        NeoLog.sink = { level, tag, message, _ -> seen += "$level|$tag|$message" }

        NeoLog.i("T", "hello")
        NeoLog.w("T", "boom")

        assertEquals(listOf("INFO|T|hello", "WARN|T|boom"), seen)
    }

    @Test
    fun `warn carries the throwable and defaults to null`() {
        var seenThrowable: Throwable? = null
        NeoLog.sink = { _, _, _, throwable -> seenThrowable = throwable }

        NeoLog.w("T", "no cause")
        assertEquals(null, seenThrowable)

        val boom = IllegalStateException("boom")
        NeoLog.w("T", "with cause", boom)
        assertEquals(boom, seenThrowable)
    }
}
