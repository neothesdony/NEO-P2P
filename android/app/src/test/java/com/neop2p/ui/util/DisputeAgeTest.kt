package com.neop2p.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Dispute age bucketing (2026-09-13): disputes have no deadline, so the UI must
 * at least say how long one has been waiting. A future timestamp (clock skew)
 * must never render a negative age.
 */
class DisputeAgeTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    @Test
    fun `null timestamp has no age`() {
        assertNull(DisputeAge.of(null, now))
    }

    @Test
    fun `an hour ago is today`() {
        assertEquals(
            DisputeAge.Age(DisputeAge.Bucket.TODAY, 0),
            DisputeAge.of(now - hour, now)
        )
    }

    @Test
    fun `three days ago is days three`() {
        assertEquals(
            DisputeAge.Age(DisputeAge.Bucket.DAYS, 3),
            DisputeAge.of(now - 3 * day, now)
        )
    }

    @Test
    fun `ten days ago is weeks one`() {
        assertEquals(
            DisputeAge.Age(DisputeAge.Bucket.WEEKS, 1),
            DisputeAge.of(now - 10 * day, now)
        )
    }

    @Test
    fun `a future timestamp is not an age`() {
        assertNull(DisputeAge.of(now + hour, now))
    }
}
