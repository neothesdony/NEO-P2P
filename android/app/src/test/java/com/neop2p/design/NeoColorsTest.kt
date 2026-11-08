package com.neop2p.design

import com.neop2p.domain.model.EscrowStatus
import org.junit.Assert.assertEquals
import org.junit.Test

@Suppress("DEPRECATION") // EscrowStatus.RESOLVING is kept for DB compatibility
class NeoColorsTest {
    @Test
    fun statuses_map_to_the_spec_families() {
        val p = NeoColors.defaultFrom(darkScheme).status
        assertEquals(p.confirmed, p.forStatus(EscrowStatus.FUNDED))
        assertEquals(p.confirmed, p.forStatus(EscrowStatus.RELEASED))
        assertEquals(p.actionRequired, p.forStatus(EscrowStatus.FUNDING))
        assertEquals(p.waiting, p.forStatus(EscrowStatus.RECEIPT_SENT))
        assertEquals(p.progress, p.forStatus(EscrowStatus.SIGNED))
        assertEquals(p.danger, p.forStatus(EscrowStatus.DISPUTED))
        assertEquals(p.arbitration, p.forStatus(EscrowStatus.RESOLVING))
        assertEquals(p.neutral, p.forStatus(EscrowStatus.CANCELLED))
        assertEquals(p.neutral, p.forStatus(EscrowStatus.REFUNDED))
        assertEquals(p.confirmed, p.forStatus(EscrowStatus.CONFIRMING))
        assertEquals(p.actionRequired, p.forStatus(EscrowStatus.PAYMENT_PENDING))
    }

    @Test
    fun light_glow_is_invisible() {
        assertEquals(0f, NeoColors.defaultFrom(lightScheme).glow.alpha)
    }

    @Test
    fun every_status_has_a_family() {
        EscrowStatus.entries.forEach { status ->
            val colors = NeoColors.defaultFrom(darkScheme).status.forStatus(status)
            // A family is only useful if it resolves to a container/content pair.
            assertEquals("container for $status", 1f, colors.container.alpha)
            assertEquals("content for $status", 1f, colors.content.alpha)
        }
    }
}
