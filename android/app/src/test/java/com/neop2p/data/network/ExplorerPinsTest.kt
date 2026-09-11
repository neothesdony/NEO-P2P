package com.neop2p.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerPinsTest {

    @Test
    fun `every explorer host is pinned`() {
        val pins = ExplorerPins.pinSpecs()
        val hosts = pins.map { it.first }.toSet()
        assertTrue("mempool.space must be pinned", hosts.contains("mempool.space"))
        assertTrue("mempool.emzy.de must be pinned", hosts.contains("mempool.emzy.de"))
        assertTrue("blockstream.info must be pinned", hosts.contains("blockstream.info"))
    }

    @Test
    fun `every pin is a valid sha256 base64 form`() {
        for ((host, pin) in ExplorerPins.pinSpecs()) {
            assertTrue("pin for $host must start with sha256/", pin.startsWith("sha256/"))
            // base64 of 32 bytes = 44 chars after the prefix.
            assertEquals("pin for $host must be 44 base64 chars", 44, pin.removePrefix("sha256/").length)
        }
    }
}
