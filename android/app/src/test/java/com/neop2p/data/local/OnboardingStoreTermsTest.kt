package com.neop2p.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingStoreTermsTest {
    @Test
    fun termsKeyIsStable() {
        assertEquals("terms_version", OnboardingStore.KEY_TERMS_VERSION)
    }

    @Test
    fun defaultAcceptedVersionIsZero() {
        assertEquals(0, OnboardingStore.DEFAULT_TERMS_VERSION)
    }
}
