package com.neop2p.data.escrow

import org.bitcoinj.core.ECKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleAddressAttestationTest {
    private val sellerKey = ECKey()
    private val attackerKey = ECKey()
    private val addr = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx" // any non-blank address string
    private val other = "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7"

    private fun sign(key: ECKey, kind: String, scope: String, address: String) =
        RoleAddressAttestation.sign(key.privateKeyAsHex, kind, scope, address)

    @Test fun `round-trip verifies`() {
        val sig = sign(sellerKey, RoleAddressAttestation.KIND_SELLER_REFUND, "escrow_1", addr)
        assertTrue(RoleAddressAttestation.verify(sellerKey.publicKeyAsHex, RoleAddressAttestation.KIND_SELLER_REFUND, "escrow_1", addr, sig))
    }

    @Test fun `wrong scope fails`() {
        val sig = sign(sellerKey, RoleAddressAttestation.KIND_SELLER_REFUND, "escrow_1", addr)
        assertFalse(RoleAddressAttestation.verify(sellerKey.publicKeyAsHex, RoleAddressAttestation.KIND_SELLER_REFUND, "escrow_2", addr, sig))
    }

    @Test fun `wrong address fails`() {
        val sig = sign(sellerKey, RoleAddressAttestation.KIND_SELLER_REFUND, "escrow_1", addr)
        assertFalse(RoleAddressAttestation.verify(sellerKey.publicKeyAsHex, RoleAddressAttestation.KIND_SELLER_REFUND, "escrow_1", other, sig))
    }

    @Test fun `wrong key fails`() {
        val sig = sign(sellerKey, RoleAddressAttestation.KIND_BUYER_PAYOUT, "offer_1", addr)
        assertFalse(RoleAddressAttestation.verify(attackerKey.publicKeyAsHex, RoleAddressAttestation.KIND_BUYER_PAYOUT, "offer_1", addr, sig))
    }

    @Test fun `x-only pubkey accepted`() {
        val sig = sign(sellerKey, RoleAddressAttestation.KIND_SELLER_REFUND, "escrow_1", addr)
        val xOnly = sellerKey.publicKeyAsHex.substring(2)
        assertTrue(RoleAddressAttestation.verify(xOnly, RoleAddressAttestation.KIND_SELLER_REFUND, "escrow_1", addr, sig))
    }

    @Test fun `malformed signature returns false`() {
        assertFalse(RoleAddressAttestation.verify(sellerKey.publicKeyAsHex, RoleAddressAttestation.KIND_SELLER_REFUND, "escrow_1", addr, "zzzz"))
    }
}
