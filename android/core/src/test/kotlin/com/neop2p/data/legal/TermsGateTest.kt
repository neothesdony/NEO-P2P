package com.neop2p.data.legal

import com.neop2p.NeoP2PConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermsGateTest {
    @Test
    fun neverAccepted_needsAcceptance() {
        assertTrue(TermsGate.needsAcceptance(0, currentVersion = 1))
    }

    @Test
    fun currentVersionAccepted_doesNotNeedAcceptance() {
        assertFalse(TermsGate.needsAcceptance(1, currentVersion = 1))
    }

    @Test
    fun futureVersionAccepted_doesNotNeedAcceptance() {
        assertFalse(TermsGate.needsAcceptance(2, currentVersion = 1))
    }

    @Test
    fun bumpedVersion_needsAcceptance() {
        assertTrue(TermsGate.needsAcceptance(1, currentVersion = 2))
    }

    @Test
    fun defaultsToConfigVersion() {
        assertFalse(TermsGate.needsAcceptance(NeoP2PConfig.TERMS_VERSION))
    }
}
