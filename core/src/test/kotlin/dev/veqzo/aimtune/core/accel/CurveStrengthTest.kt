package dev.veqzo.aimtune.core.accel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurveStrengthTest {
    @Test
    fun `strengths close to one are no curve and others are natural curves that level off at the strength`() {
        assertNull(CurveStrength.curve(1.0))
        assertNull(CurveStrength.curve(1.015))
        val rising = assertNotNull(CurveStrength.curve(1.2))
        val falling = assertNotNull(CurveStrength.curve(0.8))
        assertEquals(1.0, rising.multiplier(0.0), 1e-9)
        assertTrue(rising.multiplier(40.0) in 1.15..1.2, "rising ${rising.multiplier(40.0)}")
        assertTrue(falling.multiplier(40.0) in 0.8..0.85, "falling ${falling.multiplier(40.0)}")
        assertTrue(rising.multiplier(20.0) > rising.multiplier(3.0))
        assertTrue(falling.multiplier(20.0) < falling.multiplier(3.0))
    }

    @Test
    fun `a rising curve is clear above one and a falling curve only below the falling limit`() {
        assertTrue(CurveStrength.excludesPlain(1.2, 1.05, 1.4))
        assertFalse(CurveStrength.excludesPlain(1.2, 1.0, 1.4), "the range touches no curve")
        assertTrue(CurveStrength.excludesPlain(0.8, 0.7, 0.85))
        assertFalse(CurveStrength.excludesPlain(0.8, 0.7, 0.95), "falling with a high end between 0.90 and 1")
        assertFalse(CurveStrength.excludesPlain(0.8, 0.7, CurveStrength.FALLING_MAX_HIGH), "the edge is not clear")
        assertTrue(CurveStrength.excludesPlain(0.8, 0.7, CurveStrength.FALLING_MAX_HIGH - 0.001))
        assertFalse(CurveStrength.excludesPlain(1.01, 1.005, 1.3), "no curve at all is never clear")
    }

    @Test
    fun `the pseudo sensitivity of the search is the strength times one hundred and round trips`() {
        assertEquals(120.0, CurveStrength.toPseudo(1.2), 1e-9)
        assertEquals(0.7, CurveStrength.fromPseudo(CurveStrength.searchRange.minCm360), 1e-9)
        assertEquals(1.45, CurveStrength.fromPseudo(CurveStrength.searchRange.maxCm360), 1e-9)
    }

    @Test
    fun `a strength candidate keeps the sensitivity and reports its gain range, and no-curve strengths are refused`() {
        val rising = strengthAccel(1.25)
        assertEquals(1.0, rising.sensitivityFactor)
        assertEquals(1.25, rising.gainRange, 1e-9)
        assertEquals(1.25, strengthAccel(0.8).gainRange, 1e-9)
        assertFailsWith<IllegalArgumentException> { strengthAccel(1.01) }
    }

    @Test
    fun `with flash rounds the search reaches stronger rising curves`() {
        assertEquals(145.0, CurveStrength.searchRange(flash = false).maxCm360, 1e-9)
        assertEquals(175.0, CurveStrength.searchRange(flash = true).maxCm360, 1e-9)
        assertEquals(CurveStrength.searchRange(flash = false).minCm360, CurveStrength.searchRange(flash = true).minCm360, 1e-9)
    }
}
