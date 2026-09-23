package com.neop2p.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    @Test
    fun newerPatchIsNewer() {
        assertTrue(UpdatePolicy.isNewer("v0.1.2", "0.1.1"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(UpdatePolicy.isNewer("0.1.1", "0.1.1"))
        assertFalse(UpdatePolicy.isNewer("v0.1.1", "0.1.1"))
    }

    @Test
    fun olderVersionIsNotNewer() {
        assertFalse(UpdatePolicy.isNewer("0.1.0", "0.1.1"))
    }

    @Test
    fun numericNotLexicographicComparison() {
        assertTrue(UpdatePolicy.isNewer("1.10.0", "1.9.0"))
        assertFalse(UpdatePolicy.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun missingTrailingComponentCountsAsZero() {
        assertTrue(UpdatePolicy.isNewer("v2.0", "1.9.9"))
        assertFalse(UpdatePolicy.isNewer("1.2", "1.2.0"))
    }

    @Test
    fun malformedInputFailsClosed() {
        assertFalse(UpdatePolicy.isNewer("garbage", "1.0.0"))
        assertFalse(UpdatePolicy.isNewer("1.0.0", "garbage"))
        assertFalse(UpdatePolicy.isNewer("", "1.0.0"))
        assertFalse(UpdatePolicy.isNewer("1.0.0-rc1", "1.0.0"))
        assertFalse(UpdatePolicy.isNewer("1.0.0", "1.0.0"))
    }

    @Test
    fun shouldNotifyOnlyWhenNewerAndNotAlreadyNotified() {
        assertTrue(UpdatePolicy.shouldNotify("v0.1.2", "0.1.1", null))
        assertTrue(UpdatePolicy.shouldNotify("v0.1.2", "0.1.1", "v0.1.0"))
        assertFalse(UpdatePolicy.shouldNotify("v0.1.2", "0.1.1", "v0.1.2"))
        assertFalse(UpdatePolicy.shouldNotify("v0.1.1", "0.1.1", null))
    }
}
