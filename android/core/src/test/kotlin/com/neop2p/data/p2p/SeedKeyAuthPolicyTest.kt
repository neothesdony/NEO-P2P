package com.neop2p.data.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedKeyAuthPolicyTest {
    @Test fun `secure device with unbound key needs retrofit`() {
        assertTrue(SeedKeyAuthPolicy.needsRetrofit(deviceSecure = true, keyAuthBound = false))
    }
    @Test fun `secure device with bound key is fine`() {
        assertFalse(SeedKeyAuthPolicy.needsRetrofit(deviceSecure = true, keyAuthBound = true))
    }
    @Test fun `lockless device never retrofits`() {
        assertFalse(SeedKeyAuthPolicy.needsRetrofit(deviceSecure = false, keyAuthBound = false))
    }
}
