package com.neop2p.data.escrow

import com.neop2p.domain.model.EscrowStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowRecoveryPolicyTest {

    private val lock = 1_000L

    @Test
    fun `seller can recover only after maturity on a live escrow`() {
        assertFalse(EscrowRecoveryPolicy.canRecover(EscrowStatus.FUNDED, true, 999_000L, lock))
        assertTrue(EscrowRecoveryPolicy.canRecover(EscrowStatus.FUNDED, true, 1_000_000L, lock))
        assertFalse(EscrowRecoveryPolicy.canRecover(EscrowStatus.FUNDED, false, 1_000_000L, lock))
        assertFalse(EscrowRecoveryPolicy.canRecover(EscrowStatus.FUNDING, true, 1_000_000L, lock))
        assertFalse(EscrowRecoveryPolicy.canRecover(EscrowStatus.RELEASED, true, 1_000_000L, lock))
    }

    @Test
    fun `a v0 escrow has no recovery branch`() {
        assertFalse(EscrowRecoveryPolicy.canRecover(EscrowStatus.FUNDED, true, 1_000_000L, null))
    }

    @Test
    fun `live statuses are recoverable and finished ones are not`() {
        val live = listOf(
            EscrowStatus.FUNDED,
            EscrowStatus.PAYMENT_PENDING,
            EscrowStatus.RECEIPT_SENT,
            EscrowStatus.DISPUTED
        )
        for (s in live) {
            assertTrue("$s should be recoverable", EscrowRecoveryPolicy.canRecover(s, true, 1_000_000L, lock))
        }
        val finished = listOf(
            EscrowStatus.FUNDING,
            EscrowStatus.SIGNED,
            EscrowStatus.CONFIRMING,
            EscrowStatus.RELEASED,
            EscrowStatus.REFUNDED,
            EscrowStatus.CANCELLED
        )
        for (s in finished) {
            assertFalse("$s must not be recoverable", EscrowRecoveryPolicy.canRecover(s, true, 1_000_000L, lock))
        }
    }
}
