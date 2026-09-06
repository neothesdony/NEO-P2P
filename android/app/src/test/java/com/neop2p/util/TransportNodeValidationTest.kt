package com.neop2p.util

import com.neop2p.ui.screens.settings.validTransportPort
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportNodeValidationTest {

    @Test
    fun `valid ports are accepted`() {
        assertTrue(validTransportPort("1"))
        assertTrue(validTransportPort("42000"))
        assertTrue(validTransportPort("65535"))
        assertTrue(validTransportPort(" 42000 "))
    }

    @Test
    fun `invalid ports are rejected`() {
        assertFalse(validTransportPort("0"))
        assertFalse(validTransportPort("65536"))
        assertFalse(validTransportPort("99999"))
        assertFalse(validTransportPort(""))
        assertFalse(validTransportPort("abc"))
        assertFalse(validTransportPort("42.5"))
    }
}
