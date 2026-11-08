package com.neop2p.design

import com.neop2p.ui.design.avatarSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoAvatarTest {

    @Test
    fun seed_is_stable_and_bounded() {
        val a = avatarSeed("12D3KooWPeerAbc")
        val b = avatarSeed("12D3KooWPeerAbc")
        assertEquals(a, b)
        assertTrue(a.first in 0..359 && a.second in 0..359)
    }

    @Test
    fun different_peers_get_different_hues() {
        assertNotEquals(avatarSeed("peer-a"), avatarSeed("peer-b"))
    }

    @Test
    fun empty_id_is_still_a_valid_seed() {
        val seed = avatarSeed("")
        assertTrue(seed.first in 0..359)
        assertTrue(seed.second in 0..359)
    }

    @Test
    fun the_second_hue_is_visibly_apart_from_the_first() {
        // A two-stop gradient with near-identical stops reads as a flat disc, so
        // the second hue is offset by a fixed arc.
        listOf("a", "bb", "peer-xyz", "").forEach { id ->
            val (first, second) = avatarSeed(id)
            assertTrue("$id -> $first/$second", second != first)
        }
    }
}
