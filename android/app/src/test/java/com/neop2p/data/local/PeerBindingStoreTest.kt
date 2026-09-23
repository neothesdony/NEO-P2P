package com.neop2p.data.local

import com.neop2p.data.local.PeerBindingStore.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerBindingStoreTest {

    @Test
    fun `round-trips expected hashes and warnings`() {
        val map = mapOf(
            "peer-a" to Entry("00112233445566778899aabbccddeeff", "INVITE_MISMATCH"),
            "peer-b" to Entry(null, "IDENTITY_CHANGED")
        )
        val parsed = PeerBindingStore.parse(PeerBindingStore.encode(map))
        assertEquals("00112233445566778899aabbccddeeff", parsed["peer-a"]?.expectedHash)
        assertEquals("INVITE_MISMATCH", parsed["peer-a"]?.warning)
        assertNull(parsed["peer-b"]?.expectedHash)
        assertEquals("IDENTITY_CHANGED", parsed["peer-b"]?.warning)
    }

    @Test
    fun `garbage and empty entries are dropped`() {
        assertTrue(PeerBindingStore.parse("").isEmpty())
        assertTrue(PeerBindingStore.parse("{not json").isEmpty())
        assertTrue(PeerBindingStore.parse("""{"peer-a":{}}""").isEmpty())
    }

    @Test
    fun `a peer with only an expected hash round-trips`() {
        val parsed = PeerBindingStore.parse(PeerBindingStore.encode(mapOf("p" to Entry("abcd", null))))
        assertEquals("abcd", parsed["p"]?.expectedHash)
        assertNull(parsed["p"]?.warning)
    }
}
