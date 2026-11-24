package dev.veqzo.aimtune.core.accel.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PassiveAnalysesTest {
    private fun reach(needX: Double, needY: Double, gotX: Double, gotY: Double) = FirstReach("flick", needX, needY, gotX, gotY, 0.05, 0L, 1L)

    @Test
    fun `too few reaches give not enough data`() {
        assertEquals(PassiveResult.NotEnoughData(0, RotationAnalysis.MIN_REACHES), RotationAnalysis.analyze(emptyList()))
        assertIs<PassiveResult.NotEnoughData>(AxisRatioAnalysis.analyze(emptyList(), AxisRatio.Y_X))
        assertIs<PassiveResult.NotEnoughData>(SnapReplay.analyze(emptyList()).result)
        assertNull(ReportSpeedProfile.summarize(emptyList()))
    }

    @Test
    fun `a constant turn of the first movement is found with the sign of Raw Accel`() {
        // Movements come out turned by -5 degrees (toward up for a rightward target), so the setting to enter is +5.
        val radians = Math.toRadians(-5.0)
        val reaches = (0 until 100).map {
            val angle = Math.toRadians(it * 3.6)
            val nx = 400 * kotlin.math.cos(angle)
            val ny = 400 * kotlin.math.sin(angle)
            reach(nx, ny, nx * kotlin.math.cos(radians) - ny * kotlin.math.sin(radians), nx * kotlin.math.sin(radians) + ny * kotlin.math.cos(radians))
        }
        val result = assertIs<PassiveResult.Recommend>(RotationAnalysis.analyze(reaches))
        assertEquals(5.0, result.value, 1e-9)
    }

    @Test
    fun `a hand that goes too short to the left gets an L over R above one`() {
        val reaches = (0 until 120).map { i ->
            val nx = if (i % 2 == 0) 300.0 else -300.0
            reach(nx, 0.0, if (nx < 0) nx / 1.15 else nx, 0.0)
        }
        val result = assertIs<PassiveResult.Recommend>(AxisRatioAnalysis.analyze(reaches, AxisRatio.L_R))
        assertEquals(1.15, result.value, 1e-9)
    }

    @Test
    fun `a left or up bias alone does not move the Y over X estimate`() {
        // Right and down are the directions no ratio touches; the biased directions must not enter Y/X.
        val reaches = (0 until 240).map { i ->
            when (i % 4) {
                0 -> reach(300.0, 0.0, 300.0, 0.0)
                1 -> reach(-300.0, 0.0, -300.0 / 1.15, 0.0)
                2 -> reach(0.0, 300.0, 0.0, 300.0)
                else -> reach(0.0, -300.0, 0.0, -300.0 / 1.15)
            }
        }
        val yx = assertIs<PassiveResult.NoChange>(AxisRatioAnalysis.analyze(reaches, AxisRatio.Y_X))
        assertEquals(1.0, yx.estimate, 1e-9)
        assertEquals(1.15, assertIs<PassiveResult.Recommend>(AxisRatioAnalysis.analyze(reaches, AxisRatio.L_R)).value, 1e-9)
        assertEquals(1.15, assertIs<PassiveResult.Recommend>(AxisRatioAnalysis.analyze(reaches, AxisRatio.U_D)).value, 1e-9)
    }
}
