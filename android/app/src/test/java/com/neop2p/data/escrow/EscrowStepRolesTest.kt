package com.neop2p.data.escrow

import com.neop2p.ui.screens.escrow.EscrowStep
import com.neop2p.ui.screens.escrow.stepsForRole
import org.junit.Assert.assertEquals
import org.junit.Test

class EscrowStepRolesTest {

    @Test
    fun `seller sees fund, confirm, release`() {
        assertEquals(
            listOf(EscrowStep.FUND, EscrowStep.CONFIRM, EscrowStep.RELEASE),
            stepsForRole("SELLER")
        )
    }

    @Test
    fun `buyer sees pay and release`() {
        assertEquals(
            listOf(EscrowStep.PAY, EscrowStep.RELEASE),
            stepsForRole("BUYER")
        )
    }

    @Test
    fun `unknown role defaults to buyer-like minimal`() {
        assertEquals(listOf(EscrowStep.PAY, EscrowStep.RELEASE), stepsForRole(""))
    }
}
