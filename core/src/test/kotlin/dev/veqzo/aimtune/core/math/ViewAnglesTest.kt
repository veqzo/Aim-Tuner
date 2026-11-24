package dev.veqzo.aimtune.core.math

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewAnglesTest {
    private val tol = 1e-9

    @Test
    fun `default looks along minus z`() {
        val d = ViewAngles().direction()
        assertEquals(0.0, d.x, tol)
        assertEquals(0.0, d.y, tol)
        assertEquals(-1.0, d.z, tol)
    }

    @Test
    fun `yaw 90 looks right`() {
        val d = ViewAngles(yawDegrees = 90.0).direction()
        assertEquals(1.0, d.x, tol)
        assertEquals(0.0, d.z, tol)
    }

    @Test
    fun `positive dx turns right and positive dy looks down`() {
        val v = ViewAngles().rotatedByCounts(dx = 100, dy = 50, degreesPerCount = 0.1)
        assertEquals(10.0, v.yawDegrees, tol)
        assertEquals(-5.0, v.pitchDegrees, tol)
    }

    @Test
    fun `pitch is clamped`() {
        val up = ViewAngles().rotatedByCounts(0, -100_000, 0.1)
        val down = ViewAngles().rotatedByCounts(0, 100_000, 0.1)
        assertEquals(89.0, up.pitchDegrees, tol)
        assertEquals(-89.0, down.pitchDegrees, tol)
    }

    @Test
    fun `yaw wraps into minus 180 exclusive to 180 inclusive`() {
        assertEquals(-170.0, ViewAngles(yawDegrees = 170.0).rotatedByCounts(20, 0, 1.0).yawDegrees, tol)
        assertEquals(180.0, ViewAngles(yawDegrees = 0.0).rotatedByCounts(180, 0, 1.0).yawDegrees, tol)
        assertEquals(10.0, ViewAngles(yawDegrees = 0.0).rotatedByCounts(370, 0, 1.0).yawDegrees, tol)
    }

    @Test
    fun `full turn of counts returns to start`() {
        val v = ViewAngles().rotatedByCounts(3600, 0, 0.1)
        assertEquals(0.0, v.yawDegrees, 1e-6)
    }
}
