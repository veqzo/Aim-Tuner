package dev.veqzo.aimtune.core.sim

import dev.veqzo.aimtune.core.accel.RawAccelArgs
import dev.veqzo.aimtune.core.accel.RawAccelMode
import dev.veqzo.aimtune.core.accel.RawAccelModifier
import dev.veqzo.aimtune.core.accel.RawAccelProfile
import dev.veqzo.aimtune.core.input.MouseEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandGeometryTest {
    private fun correcting(hand: HandGeometry) = RawAccelProfile(
        "fix", RawAccelArgs(RawAccelMode.NOACCEL), outputDpi = 1000.0, deviceDpi = 1000.0,
        yxRatio = hand.verticalGain, lrRatio = hand.leftBias, udRatio = hand.upBias, rotationDegrees = hand.gripRotationDegrees,
    )

    @Test
    fun `the matching Raw Accel settings undo the hand exactly`() {
        val hands = listOf(
            HandGeometry(gripRotationDegrees = 6.0),
            HandGeometry(gripRotationDegrees = -10.0),
            HandGeometry(verticalGain = 0.8),
            HandGeometry(leftBias = 1.15),
            HandGeometry(upBias = 0.85),
            HandGeometry(gripRotationDegrees = 3.0, verticalGain = 1.25, leftBias = 0.85, upBias = 1.15),
        )
        val random = Random(1)
        for (hand in hands) {
            val driver = RawAccelModifier(correcting(hand))
            repeat(200) { i ->
                val ix = random.nextInt(-40, 41).toDouble()
                val iy = random.nextInt(-40, 41).toDouble()
                if (ix == 0.0 && iy == 0.0) return@repeat
                val (hx, hy) = hand.distort(ix, iy)
                // The driver takes whole counts; scale up so the hand's fractions do not matter, then compare directions and lengths.
                val scale = 1000.0
                val out = driver.modifyContinuous(MouseEvent(i * 1_000_000L, Math.round(hx * scale).toInt(), Math.round(hy * scale).toInt()))
                assertEquals(ix * scale, out.x, 1.0, "x of $hand for ($ix, $iy)")
                assertEquals(iy * scale, out.y, 1.0, "y of $hand for ($ix, $iy)")
            }
        }
    }

    @Test
    fun `a positive grip rotation turns movements by minus theta in the frame of the driver`() {
        val (x, y) = HandGeometry(gripRotationDegrees = 10.0).distort(100.0, 0.0)
        assertTrue(x > 0.0 && y < 0.0, "a rightward movement comes out slightly upward, ($x, $y)")
        assertEquals(-10.0, Math.toDegrees(kotlin.math.atan2(y, x)), 1e-9)
    }

    @Test
    fun `the default hand does not touch the counts`() {
        assertFalse(HandGeometry.NEUTRAL.distortsCounts)
        assertEquals(SimulatedPlayer(), SimulatedPlayer(hand = HandGeometry()))
    }
}
