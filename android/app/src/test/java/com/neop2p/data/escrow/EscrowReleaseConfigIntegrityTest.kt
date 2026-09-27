package com.neop2p.data.escrow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowReleaseConfigIntegrityTest {
    @Test fun `both signatures are required`() {
        assertTrue(EscrowService.releaseConfigIntegrityOk(feeWalletOk = true, arbitratorOk = true))
        assertFalse(EscrowService.releaseConfigIntegrityOk(feeWalletOk = false, arbitratorOk = true))
        assertFalse(EscrowService.releaseConfigIntegrityOk(feeWalletOk = true, arbitratorOk = false))
        assertFalse(EscrowService.releaseConfigIntegrityOk(feeWalletOk = false, arbitratorOk = false))
    }
}