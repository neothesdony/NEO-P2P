package com.neop2p.data.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletAddressStateStoreTest {

    @Test
    fun `round-trips pointers including reserved`() {
        val p = HdPointers(nextExternal = 7, nextChange = 3, reserved = setOf(7, 9, 11))
        val parsed = WalletAddressStateStore.parse(WalletAddressStateStore.toJson(p))
        assertEquals(p, parsed)
    }

    @Test
    fun `absent blob degrades to defaults`() {
        assertEquals(HdPointers(), WalletAddressStateStore.parse(null))
        assertEquals(HdPointers(), WalletAddressStateStore.parse(""))
        assertEquals(HdPointers(), WalletAddressStateStore.parse("   "))
    }

    @Test
    fun `corrupt blob degrades to the full default not a partial pointer`() {
        val p = WalletAddressStateStore.parse("[not json")
        assertEquals(HdPointers(), p)
        assertEquals(emptySet<Int>(), p.reserved)
        assertEquals(0, p.nextExternal)
        assertEquals(0, p.nextChange)
    }

    @Test
    fun `unknown extra field does not throw`() {
        val parsed = WalletAddressStateStore.parse(
            """{"nextExternal":2,"nextChange":1,"reserved":[2],"futureField":"x"}"""
        )
        assertEquals(HdPointers(nextExternal = 2, nextChange = 1, reserved = setOf(2)), parsed)
    }

    @Test
    fun `negative indices are sanitized`() {
        val parsed = WalletAddressStateStore.parse(
            """{"nextExternal":-4,"nextChange":-1,"reserved":[-2,3]}"""
        )
        assertEquals(0, parsed.nextExternal)
        assertEquals(0, parsed.nextChange)
        assertEquals(setOf(3), parsed.reserved)
    }

    @Test
    fun `json shape is stable and non-empty`() {
        val json = WalletAddressStateStore.toJson(HdPointers())
        assertTrue(json.contains("nextExternal"))
        assertTrue(json.contains("nextChange"))
        assertTrue(json.contains("reserved"))
    }
}
