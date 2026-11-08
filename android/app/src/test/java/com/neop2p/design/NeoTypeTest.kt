package com.neop2p.design

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neop2p.ui.design.NeoSpacing
import com.neop2p.ui.design.NeoType
import com.neop2p.ui.design.neoTypography
import org.junit.Assert.assertEquals
import org.junit.Test

class NeoTypeTest {
    @Test
    fun numeric_roles_have_the_spec_metrics() {
        val t = NeoType.defaultFrom(Typography())
        assertEquals(34f, t.moneyHero.fontSize.value)
        assertEquals(38f, t.moneyHero.lineHeight.value)
        assertEquals(24f, t.moneyLarge.fontSize.value)
        assertEquals(14f, t.moneySmall.fontSize.value)
        assertEquals(11f, t.monoMicro.fontSize.value)
    }

    @Test
    fun spacing_is_the_spec_grid() {
        assertEquals(4.dp, NeoSpacing.Default.xs)
        assertEquals(20.dp, NeoSpacing.Default.screenPadding)
        assertEquals(24.dp, NeoSpacing.Default.sectionGap)
        assertEquals(16.dp, NeoSpacing.Default.gutter)
    }

    @Test
    fun spacing_is_ordered_from_tight_to_loose() {
        val s = NeoSpacing.Default
        val ordered = listOf(s.xxs, s.xs, s.sm, s.md, s.lg, s.xl, s.xxl)
        assertEquals(ordered.sorted(), ordered)
    }

    @Test
    fun the_m3_scale_is_replaced_not_appended_to() {
        val t = neoTypography()
        assertEquals(40.sp, t.displayLarge.fontSize)
        assertEquals(14.sp, t.labelLarge.fontSize)
        // Numeric-bearing roles carry tabular figures so digits never jump.
        assertEquals("tnum", t.titleMedium.fontFeatureSettings)
    }
}
