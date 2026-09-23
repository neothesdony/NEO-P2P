package com.neop2p.data.local

import com.neop2p.data.escrow.EscrowScriptTemplate
import com.neop2p.data.local.entity.EscrowEntity
import com.neop2p.domain.model.Escrow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C9 (Phase 1): the v31→v32 `script_template` / `cltv_locktime` columns must
 * survive the entity↔domain round trip, and a legacy NULL template must read
 * back as V0 (no maturity) so pre-upgrade escrows keep today's behaviour.
 */
class EscrowTemplateMapperTest {

    private fun entity(scriptTemplate: String?, cltv: Long?) = EscrowEntity(
        escrow_id = "e1",
        offer_id = "o1",
        deposit_amount_sats = 1_000L,
        trade_amount_sats = 900L,
        fee_amount_sats = 5L,
        fee_address = "fee",
        buyer_peer_id = "buyer",
        seller_peer_id = "seller",
        script_template = scriptTemplate,
        cltv_locktime = cltv
    )

    @Test
    fun `legacy null template maps to V0 and no maturity`() {
        val domain = entity(null, null).toDomain()
        assertEquals(EscrowScriptTemplate.MULTISIG_2OF3_V0, domain.scriptTemplate)
        assertNull(domain.cltvLocktime)
    }

    @Test
    fun `v1 template and maturity survive the entity round trip`() {
        val domain = entity(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1.id, 1_790_000_000L).toDomain()
        assertEquals(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1, domain.scriptTemplate)
        assertEquals(1_790_000_000L, domain.cltvLocktime!!)

        val back = domain.toEntity()
        assertEquals(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1.id, back.script_template)
        assertEquals(1_790_000_000L, back.cltv_locktime!!)
    }

    @Test
    fun `v0 domain defaults persist as V0 with no maturity`() {
        val domain = Escrow(
            escrowId = "e1",
            offerId = "o1",
            depositAmountSats = 1_000L,
            tradeAmountSats = 900L,
            feeAmountSats = 5L,
            feeAddress = "fee",
            buyerPeerId = "buyer",
            sellerPeerId = "seller"
        )
        val back = domain.toEntity()
        assertEquals(EscrowScriptTemplate.MULTISIG_2OF3_V0.id, back.script_template)
        assertNull(back.cltv_locktime)
    }
}
