package com.neop2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cash meetup was removed as a tradeable rail: keeping the enum entry would
 * re-expose it in the create-offer picker and the market filter while the
 * escrow flow has no cash-specific handling (a cash offer inherits the
 * bank-transfer unique-code/BI-FAST UI).
 */
class FiatMethodCatalogTest {

    @Test
    fun `cash meetup is not a selectable method`() {
        assertNull(FiatMethod.fromId("cash"))
        assertTrue(FiatMethod.entries.none { it.id == "cash" })
        assertTrue(FiatMethod.entries.none { it.displayNameId.contains("Cash", ignoreCase = true) })
    }

    @Test
    fun `every catalog entry round-trips through fromId`() {
        FiatMethod.entries.forEach { m ->
            assertEquals(m, FiatMethod.fromId(m.id))
        }
    }

    @Test
    fun `offer method cap matches the catalog size`() {
        assertEquals(FiatMethod.entries.size, NeoP2PConfig.MAX_OFFER_FIAT_METHODS)
    }
}
