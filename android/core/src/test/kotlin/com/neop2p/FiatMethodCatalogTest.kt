package com.neop2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cash meetup was removed as a tradeable rail (2026-09-13) and QRIS was
 * removed for this version (2026-09-25, plumbing retained). A removed rail
 * must not remain selectable anywhere.
 */
class FiatMethodCatalogTest {

    @Test
    fun `cash meetup is not a selectable method`() {
        assertNull(FiatMethod.fromId("cash"))
        assertTrue(FiatMethod.entries.none { it.id == "cash" })
        assertTrue(FiatMethod.entries.none { it.displayNameId.contains("Cash", ignoreCase = true) })
    }

    @Test
    fun `qris is not a selectable method`() {
        assertNull(FiatMethod.fromId("qris"))
        assertTrue(FiatMethod.entries.none { it.id == "qris" })
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

    @Test
    fun `new indonesian banks are in the bank transfer category`() {
        listOf("bsi", "btn", "permata", "danamon", "ocbc", "maybank").forEach { id ->
            assertEquals(FiatCategory.BANK_TRANSFER, FiatMethod.fromId(id)?.category)
        }
    }

    @Test
    fun `digital money category contains exactly the five wallets`() {
        assertEquals(
            setOf("gopay", "ovo", "dana", "shopeepay", "linkaja"),
            FiatMethod.inCategory(FiatCategory.DIGITAL_MONEY).map { it.id }.toSet()
        )
    }

    @Test
    fun `every entry declares a category`() {
        FiatMethod.entries.forEach { m ->
            assertTrue(m.category == FiatCategory.BANK_TRANSFER || m.category == FiatCategory.DIGITAL_MONEY)
        }
    }
}
