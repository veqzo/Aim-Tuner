package dev.veqzo.aimtune.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChartSpecTest {
    @Test
    fun `ticks are round numbers inside the range`() {
        assertEquals(listOf(0.0, 50.0, 100.0), niceTicks(0.0, 100.0))
        val ticks = niceTicks(38.4, 46.1)
        assertTrue(ticks.all { it in 38.4..46.1 })
        assertTrue(ticks.size in 3..8, "$ticks")
        val steps = ticks.zipWithNext { a, b -> b - a }
        assertTrue(steps.all { kotlin.math.abs(it - steps[0]) < 1e-9 }, "even steps in $ticks")
    }

    @Test
    fun `small ranges give fractional ticks`() {
        val ticks = niceTicks(0.8, 1.6)
        assertTrue(ticks.first() >= 0.8 && ticks.last() <= 1.6)
        assertTrue(ticks.size >= 3)
    }

    @Test
    fun `an empty range is rejected`() {
        assertFailsWith<IllegalArgumentException> { niceTicks(5.0, 5.0) }
        assertFailsWith<IllegalArgumentException> { ChartSpec(1.0, 1.0, 0.0, 1.0) }
    }

    @Test
    fun `tick text has no trailing zeros`() {
        assertEquals("40", formatTick(40.0))
        assertEquals("2.5", formatTick(2.5))
        assertEquals("0", formatTick(-0.0))
    }
}
