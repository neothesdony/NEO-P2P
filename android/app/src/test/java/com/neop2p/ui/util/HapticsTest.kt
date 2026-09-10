package com.neop2p.ui.util

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Test

class HapticsTest {

    @Test
    fun `irreversible broadcasts use long press`() {
        assertEquals(HapticFeedbackType.LongPress, hapticFor(MoneyAction.FUND))
        assertEquals(HapticFeedbackType.LongPress, hapticFor(MoneyAction.SEND_BTC))
    }

    @Test
    fun `state transitions use confirm`() {
        assertEquals(HapticFeedbackType.Confirm, hapticFor(MoneyAction.MARK_PAID))
        assertEquals(HapticFeedbackType.Confirm, hapticFor(MoneyAction.CONFIRM_RELEASE))
    }

    @Test
    fun `opening a dispute is as irreversible as a broadcast`() {
        assertEquals(HapticFeedbackType.LongPress, hapticFor(MoneyAction.DISPUTE))
    }
}
