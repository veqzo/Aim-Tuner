package dev.veqzo.aimtune.core.units

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FovTest {
    @Test
    fun `vertical fov is returned unchanged`() {
        assertEquals(70.0, Fov(70.0, FovType.VERTICAL).verticalDegrees(16.0 / 9.0), absoluteTolerance = 1e-12)
    }

    @Test
    fun `horizontal 103 at 16 9 converts to about 70_5 vertical`() {
        assertEquals(70.5, Fov(103.0, FovType.HORIZONTAL).verticalDegrees(16.0 / 9.0), absoluteTolerance = 0.1)
    }

    @Test
    fun `horizontal equals vertical at aspect 1`() {
        assertEquals(90.0, Fov(90.0, FovType.HORIZONTAL).verticalDegrees(1.0), absoluteTolerance = 1e-9)
    }

    @Test
    fun `rejects invalid values`() {
        assertFailsWith<IllegalArgumentException> { Fov(0.0, FovType.HORIZONTAL) }
        assertFailsWith<IllegalArgumentException> { Fov(180.0, FovType.HORIZONTAL) }
        assertFailsWith<IllegalArgumentException> { Fov(90.0, FovType.HORIZONTAL).verticalDegrees(0.0) }
    }
}
