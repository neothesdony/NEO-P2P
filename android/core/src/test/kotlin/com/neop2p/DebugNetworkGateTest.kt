package com.neop2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugNetworkGateTest {

    @Test
    fun `debug + mainnet is forbidden`() {
        assertTrue(DebugNetworkGate.forbidsMainnetInDebug(isDebuggable = true, network = "mainnet"))
    }

    @Test
    fun `debug + testnet is allowed`() {
        assertFalse(DebugNetworkGate.forbidsMainnetInDebug(isDebuggable = true, network = "testnet"))
    }

    @Test
    fun `release + mainnet is allowed`() {
        assertFalse(DebugNetworkGate.forbidsMainnetInDebug(isDebuggable = false, network = "mainnet"))
    }

    @Test
    fun `release + testnet is allowed`() {
        assertFalse(DebugNetworkGate.forbidsMainnetInDebug(isDebuggable = false, network = "testnet"))
    }

    @Test
    fun `unknown network in debug is allowed unless mainnet`() {
        assertFalse(DebugNetworkGate.forbidsMainnetInDebug(isDebuggable = true, network = "regtest"))
    }
}
