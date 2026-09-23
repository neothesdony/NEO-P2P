package com.neop2p.data.portability

import com.neop2p.data.local.entity.EscrowEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BundleMapperTest {
    @Test
    fun escrowByteArraysRoundTripThroughBase64() {
        val entity = EscrowEntity(
            escrow_id = "e1",
            offer_id = "o1",
            deposit_amount_sats = 1000,
            trade_amount_sats = 900,
            fee_amount_sats = 5,
            fee_address = "bc1qfee",
            buyer_peer_id = "b",
            seller_peer_id = "s",
            psbt_unsigned = byteArrayOf(1, 2, 3, -1)
        )
        val back = entity.toBundle().toEntity()
        assertArrayEquals(entity.psbt_unsigned, back.psbt_unsigned)
        assertEquals(entity.escrow_id, back.escrow_id)
        assertEquals(entity.status, back.status)
    }

    @Test
    fun nullByteArraysStayNull() {
        val entity = EscrowEntity(
            escrow_id = "e2",
            offer_id = "o2",
            deposit_amount_sats = 1,
            trade_amount_sats = 1,
            fee_amount_sats = 1,
            fee_address = "bc1qfee",
            buyer_peer_id = "b",
            seller_peer_id = "s"
        )
        assertEquals(null, entity.toBundle().toEntity().psbt_unsigned)
    }
}
