package com.neop2p.ui.screens.home

import com.neop2p.ui.screens.home.NotifRationaleDecision
import com.neop2p.ui.screens.home.notifRationaleDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class NotifRationaleTest {

    @Test
    fun `no decision when permission already granted`() {
        assertEquals(NotifRationaleDecision.NONE, notifRationaleDecision(hasPermission = true, rationaleShown = false))
    }

    @Test
    fun `show rationale on first run`() {
        assertEquals(NotifRationaleDecision.SHOW_RATIONALE, notifRationaleDecision(hasPermission = false, rationaleShown = false))
    }

    @Test
    fun `request directly once rationale has been shown`() {
        assertEquals(NotifRationaleDecision.REQUEST, notifRationaleDecision(hasPermission = false, rationaleShown = true))
    }
}
