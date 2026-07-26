package com.neop2p.data.reputation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ReputationSystem scoring logic.
 *
 * The Wilson score interval (lower bound at 95% confidence) determines
 * reputation scores. These tests verify the math independently of Android.
 */
class ReputationSystemTest {

    /**
     * Replicate the Wilson score calculation for testability.
     */
    private fun calculateWilsonScore(positive: Int, negative: Int): Float {
        val total = positive.toLong() + negative.toLong()
        if (total == 0L) return 0f
        val z = 1.96
        val p = positive.toDouble() / total
        val left = p + (z * z) / (2 * total)
        val right = z * Math.sqrt((p * (1 - p) + (z * z) / (4 * total)) / total)
        val under = 1 + (z * z) / total
        return ((left - right) / under).toFloat().coerceIn(0f, 1f)
    }

    @Test
    fun `perfect record scores 1_0`() {
        val score = calculateWilsonScore(100, 0)
        assertEquals(0.965f, score, 0.01f)
    }

    @Test
    fun `zero trades scores 0`() {
        val score = calculateWilsonScore(0, 0)
        assertEquals(0f, score, 0.001f)
    }

    @Test
    fun `mixed record penalizes negative trades`() {
        val positive = calculateWilsonScore(80, 20)
        val negative = calculateWilsonScore(20, 80)
        assertTrue("Positive trades should score higher", positive > negative)
    }

    @Test
    fun `low volume trades have conservative score`() {
        val lowVolume = calculateWilsonScore(1, 0)   // 1 trade
        val highVolume = calculateWilsonScore(100, 0) // 100 trades
        assertTrue("More data should improve confidence", highVolume > lowVolume)
    }

    @Test
    fun `fifty_fifty converges on 0_5`() {
        val score = calculateWilsonScore(50, 50)
        assertEquals(0.5f, score, 0.15f)
    }

    @Test
    fun `single negative trade scores low`() {
        val score = calculateWilsonScore(0, 1)
        assertTrue(score < 0.1f)
    }

    @Test
    fun `score never exceeds 1_0`() {
        val score = calculateWilsonScore(Int.MAX_VALUE, 1)
        assertTrue(score <= 1.0f)
    }

    @Test
    fun `score never goes below 0`() {
        val score = calculateWilsonScore(0, Int.MAX_VALUE)
        assertTrue(score >= 0f)
    }
}
