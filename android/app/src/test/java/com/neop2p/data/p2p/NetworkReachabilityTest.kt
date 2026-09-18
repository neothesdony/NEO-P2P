package com.neop2p.data.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReachabilityTest {

    @Test
    fun `wifi-only passes on wifi and unknown but not cellular or none`() {
        assertTrue(NetworkReachability.passes(NetworkRestriction.WIFI_ONLY, CurrentTransport.WIFI_LIKE))
        assertTrue(NetworkReachability.passes(NetworkRestriction.WIFI_ONLY, CurrentTransport.UNKNOWN))
        assertFalse(NetworkReachability.passes(NetworkRestriction.WIFI_ONLY, CurrentTransport.CELLULAR))
        assertFalse(NetworkReachability.passes(NetworkRestriction.WIFI_ONLY, CurrentTransport.NONE))
    }

    @Test
    fun `any passes whenever a network exists`() {
        assertTrue(NetworkReachability.passes(NetworkRestriction.ANY, CurrentTransport.WIFI_LIKE))
        assertTrue(NetworkReachability.passes(NetworkRestriction.ANY, CurrentTransport.CELLULAR))
        assertTrue(NetworkReachability.passes(NetworkRestriction.ANY, CurrentTransport.UNKNOWN))
        assertFalse(NetworkReachability.passes(NetworkRestriction.ANY, CurrentTransport.NONE))
    }

    @Test
    fun `autointerface wifi-only disables on cellular and none`() {
        assertTrue(NetworkReachability.autoInterfaceEnabled(CurrentTransport.WIFI_LIKE, wifiOnly = true))
        assertTrue(NetworkReachability.autoInterfaceEnabled(CurrentTransport.UNKNOWN, wifiOnly = true))
        assertFalse(NetworkReachability.autoInterfaceEnabled(CurrentTransport.CELLULAR, wifiOnly = true))
        assertFalse(NetworkReachability.autoInterfaceEnabled(CurrentTransport.NONE, wifiOnly = true))
    }

    @Test
    fun `autointerface unrestricted stays on except with no network`() {
        assertTrue(NetworkReachability.autoInterfaceEnabled(CurrentTransport.CELLULAR, wifiOnly = false))
        assertFalse(NetworkReachability.autoInterfaceEnabled(CurrentTransport.NONE, wifiOnly = false))
    }
}
