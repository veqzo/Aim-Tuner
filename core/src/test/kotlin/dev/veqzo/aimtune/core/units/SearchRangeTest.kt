package dev.veqzo.aimtune.core.units

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchRangeTest {
    @Test
    fun `styles give lower and narrower ranges for wrist than for arm`() {
        val wrist = searchRange(AimStyle.WRIST, currentCm360 = 28.0, padWidthCm = 90.0)
        val arm = searchRange(AimStyle.ARM, currentCm360 = 60.0, padWidthCm = 90.0)
        assertTrue(wrist.maxCm360 < arm.maxCm360)
        assertTrue(wrist.minCm360 < arm.minCm360)
        assertTrue(wrist.maxCm360 - wrist.minCm360 < arm.maxCm360 - arm.minCm360)
    }

    @Test
    fun `current value is always inside the range with room on both sides`() {
        for (style in AimStyle.entries) {
            for (current in listOf(0.5, 8.0, 20.0, 41.0, 65.6, 90.0, 200.0, 3000.0)) {
                for (pad in listOf(20.0, 32.0, 90.0)) {
                    val r = searchRange(style, current, padWidthCm = pad)
                    assertTrue(current in r, "$style $current $pad -> $r")
                    assertTrue(r.minCm360 <= current / 1.19 && r.maxCm360 >= current * 1.19, "$style $current $pad has no room on both sides: $r")
                    assertTrue(r.maxCm360 / r.minCm360 <= 6.0 + 1e-9, "$style $current $pad is too wide: $r")
                }
            }
        }
    }

    @Test
    fun `a small pad does not cut off sensitivities above the current value`() {
        // The case from the review: mixed style, 32 cm pad, 65.6 cm per 360. It used to end 10 % above the current value.
        val r = searchRange(AimStyle.MIXED, currentCm360 = 65.6, padWidthCm = 32.0)
        assertTrue(r.maxCm360 >= 65.6 * 1.6 - 1e-9, "range $r")
        assertTrue(r.minCm360 <= 30.0 + 1e-9)
        assertEquals(searchRange(AimStyle.MIXED, 65.6, 90.0), r, "the pad width does not shape the range")
        // The current value sits in the middle of the range on the log scale, not at an end.
        val position = kotlin.math.ln(65.6 / r.minCm360) / kotlin.math.ln(r.maxCm360 / r.minCm360)
        assertTrue(position in 0.3..0.7, "position $position")
    }

    @Test
    fun `the style window is used when the current value is inside it`() {
        val r = searchRange(AimStyle.MIXED, currentCm360 = 40.0, padWidthCm = 90.0)
        assertTrue(r.minCm360 <= 30.0 && r.maxCm360 >= 50.0)
    }

    @Test
    fun `half a turn warns about small pads`() {
        assertFalse(doesHalfTurnFitPad(cm360 = 65.6, padWidthCm = 27.0))
        assertTrue(doesHalfTurnFitPad(cm360 = 65.6, padWidthCm = 45.0))
        assertFalse(doesTurnFitPad(cm360 = 60.0, padWidthCm = 45.0))
    }

    @Test
    fun `the table is configurable`() {
        val table = SearchRangeTable(mapOf(AimStyle.WRIST to (10.0 to 20.0), AimStyle.MIXED to (20.0 to 40.0), AimStyle.ARM to (40.0 to 80.0)))
        val r = searchRange(AimStyle.WRIST, currentCm360 = 15.0, padWidthCm = 90.0, table = table)
        assertEquals(9.375, r.minCm360, 1e-9)
        assertEquals(24.0, r.maxCm360, 1e-9)
    }

    @Test
    fun `invalid input is rejected`() {
        assertFailsWith<IllegalArgumentException> { searchRange(AimStyle.MIXED, 0.0, 45.0) }
        assertFailsWith<IllegalArgumentException> { searchRange(AimStyle.MIXED, 40.0, -1.0) }
        assertFailsWith<IllegalArgumentException> { SearchRangeTable(mapOf(AimStyle.WRIST to (20.0 to 10.0))) }
    }

    @Test
    fun `log midpoint lies between the bounds`() {
        val r = SearchRange(20.0, 80.0)
        assertEquals(40.0, r.geometricMid, 1e-9)
    }
}
