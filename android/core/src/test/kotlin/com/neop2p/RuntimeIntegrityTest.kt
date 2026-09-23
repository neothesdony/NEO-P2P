package com.neop2p

import com.neop2p.RuntimeIntegrity.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeIntegrityTest {

    private fun clean() = RuntimeIntegrity.Signals(
        isDebuggable = false,
        isDebuggerAttached = false,
        isEmulator = false,
        isRooted = false,
        adbEnabled = false,
        devOptionsEnabled = false,
    )

    @Test
    fun `clean release device is ok`() {
        assertEquals(Level.OK, RuntimeIntegrity.assess(clean()))
    }

    @Test
    fun `attached debugger blocks a money action`() {
        assertEquals(Level.BLOCK, RuntimeIntegrity.assess(clean().copy(isDebuggerAttached = true)))
        assertTrue(RuntimeIntegrity.blocked(clean().copy(isDebuggerAttached = true)))
    }

    @Test
    fun `root adb dev-options and emulator warn but do not block`() {
        assertEquals(Level.WARN, RuntimeIntegrity.assess(clean().copy(isRooted = true)))
        assertEquals(Level.WARN, RuntimeIntegrity.assess(clean().copy(adbEnabled = true)))
        assertEquals(Level.WARN, RuntimeIntegrity.assess(clean().copy(devOptionsEnabled = true)))
        assertEquals(Level.WARN, RuntimeIntegrity.assess(clean().copy(isEmulator = true)))
        assertFalse(RuntimeIntegrity.blocked(clean().copy(isRooted = true)))
    }

    @Test
    fun `debug build warns`() {
        assertEquals(Level.WARN, RuntimeIntegrity.assess(clean().copy(isDebuggable = true)))
    }

    @Test
    fun `debugger outranks every other signal`() {
        val worst = clean().copy(
            isDebuggable = true, isRooted = true, adbEnabled = true,
            devOptionsEnabled = true, isEmulator = true, isDebuggerAttached = true,
        )
        assertEquals(Level.BLOCK, RuntimeIntegrity.assess(worst))
    }
}
