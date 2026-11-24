package dev.veqzo.aimtune.core.units

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SensitivityTest {
    @Test
    fun `valorant example 800 dpi sens 0_249 is about 65_6 cm per 360`() {
        assertEquals(65.6, cm360(dpi = 800.0, sensitivity = 0.249, yawDegreesPerCount = 0.07), absoluteTolerance = 0.05)
    }

    @Test
    fun `sensitivityFor inverts cm360`() {
        val cm = cm360(dpi = 1600.0, sensitivity = 0.4, yawDegreesPerCount = 0.07)
        assertEquals(0.4, sensitivityFor(dpi = 1600.0, yawDegreesPerCount = 0.07, cm360 = cm), absoluteTolerance = 1e-9)
    }

    @Test
    fun `degreesPerCount equals yaw times sens`() {
        val cm = cm360(dpi = 800.0, sensitivity = 0.249, yawDegreesPerCount = 0.07)
        assertEquals(0.249 * 0.07, degreesPerCount(dpi = 800.0, cm360 = cm), absoluteTolerance = 1e-12)
    }

    @Test
    fun `one full turn of counts rotates 360 degrees`() {
        val dpi = 800.0
        val cm = 40.0
        val countsPerTurn = dpi * cm / 2.54
        assertEquals(360.0, countsPerTurn * degreesPerCount(dpi, cm), absoluteTolerance = 1e-9)
    }

    @Test
    fun `rejects non positive input`() {
        assertFailsWith<IllegalArgumentException> { cm360(0.0, 1.0, 0.07) }
        assertFailsWith<IllegalArgumentException> { degreesPerCount(800.0, -1.0) }
        assertFailsWith<IllegalArgumentException> { sensitivityFor(800.0, Double.NaN, 40.0) }
    }
}
