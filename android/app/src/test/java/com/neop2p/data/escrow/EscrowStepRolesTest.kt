package com.neop2p.data.escrow

import com.neop2p.domain.model.EscrowStatus
import com.neop2p.ui.design.NeoBandKind
import com.neop2p.ui.design.escrowBandFor
import com.neop2p.ui.screens.escrow.EscrowStep
import com.neop2p.ui.screens.escrow.stepsForRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@Suppress("DEPRECATION") // EscrowStatus.RESOLVING is a DB-compat alias for DISPUTED
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

    @Test
    fun `buyer never sees a fund step`() {
        assertFalse(stepsForRole("BUYER").any { it == EscrowStep.FUND })
    }

    @Test
    fun `non-linear statuses render a band not a spine`() {
        assertEquals(NeoBandKind.DISPUTE, escrowBandFor(EscrowStatus.DISPUTED))
        assertEquals(NeoBandKind.DISPUTE, escrowBandFor(EscrowStatus.RESOLVING))
        assertEquals(NeoBandKind.TERMINAL, escrowBandFor(EscrowStatus.CANCELLED))
        assertEquals(NeoBandKind.TERMINAL, escrowBandFor(EscrowStatus.REFUNDED))
        assertEquals(NeoBandKind.TERMINAL, escrowBandFor(EscrowStatus.RELEASED))
    }

    @Test
    fun `happy-path statuses keep the spine`() {
        listOf(
            EscrowStatus.FUNDING,
            EscrowStatus.FUNDED,
            EscrowStatus.SIGNED,
            EscrowStatus.PAYMENT_PENDING,
            EscrowStatus.RECEIPT_SENT,
            EscrowStatus.CONFIRMING
        ).forEach { assertEquals(it.name, NeoBandKind.SPINE, escrowBandFor(it)) }
    }
}
