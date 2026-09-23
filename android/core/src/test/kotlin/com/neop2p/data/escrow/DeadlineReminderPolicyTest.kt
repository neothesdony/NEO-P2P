package com.neop2p.data.escrow

import com.neop2p.domain.model.EscrowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeadlineReminderPolicyTest {

    @Test
    fun `funding reminder fires in the last 15 minutes only once`() {
        assertEquals(
            DeadlineReminderPolicy.Reminder.FUNDING_T_15M,
            DeadlineReminderPolicy.due(EscrowStatus.FUNDING, 15 * 60_000L + 1, 30 * 60_000L, 60 * 60_000L)
        )
        assertNull(DeadlineReminderPolicy.due(EscrowStatus.FUNDING, 5 * 60_000L, 30 * 60_000L, 60 * 60_000L))
    }

    @Test
    fun `payment reminder fires in the last hour`() {
        assertEquals(
            DeadlineReminderPolicy.Reminder.PAYMENT_T_1H,
            DeadlineReminderPolicy.due(EscrowStatus.PAYMENT_PENDING, 60 * 60_000L + 1, 30 * 60_000L, 60 * 60_000L)
        )
    }

    @Test
    fun `no reminder outside the windows`() {
        assertNull(DeadlineReminderPolicy.due(EscrowStatus.FUNDING, 30 * 60_000L, 30 * 60_000L, 60 * 60_000L))
        assertNull(DeadlineReminderPolicy.due(EscrowStatus.RELEASED, 60 * 60_000L + 1, 30 * 60_000L, 60 * 60_000L))
    }
}
