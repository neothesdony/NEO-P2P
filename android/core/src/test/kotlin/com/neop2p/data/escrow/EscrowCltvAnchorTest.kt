package com.neop2p.data.escrow

import org.bitcoinj.crypto.ECKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowCltvAnchorTest {

    private val lockSec = 1_700_000_000L                       // an already-matured locktime
    private val forgedCreatedAt = lockSec * 1000L - EscrowScripts.MATURITY_MS
    private val lockedAt = lockSec * 1000L + 10L * EscrowScripts.MATURITY_MS // recent local anchor

    @Test fun `buyer uses the local offer match time, never the peer created_at`() {
        assertEquals(
            lockedAt,
            EscrowCltvGate.trustedMaturityFloorMs(lockedAt, forgedCreatedAt, localIsCreator = false)
        )
    }

    @Test fun `buyer with no local match anchor fails closed`() {
        assertNull(EscrowCltvGate.trustedMaturityFloorMs(null, forgedCreatedAt, localIsCreator = false))
        assertNull(EscrowCltvGate.trustedMaturityFloorMs(0L, forgedCreatedAt, localIsCreator = false))
    }

    @Test fun `creator may fall back to its own created_at`() {
        assertEquals(
            forgedCreatedAt,
            EscrowCltvGate.trustedMaturityFloorMs(null, forgedCreatedAt, localIsCreator = true)
        )
    }

    @Test fun `a backdated created_at cannot make a matured branch pass against a local anchor`() {
        val buyer = ECKey(); val seller = ECKey(); val arb = ECKey()
        val matured = EscrowScripts.build(
            EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, buyer, seller, arb, lockSec
        ).program
        // The old bug: the forged floor makes the matured branch pass.
        assertTrue(EscrowCltvGate.verify(matured, seller.publicKeyAsHex, forgedCreatedAt).ok)
        // The fix: the local anchor rejects it.
        assertFalse(EscrowCltvGate.verify(matured, seller.publicKeyAsHex, lockedAt).ok)
    }
}
