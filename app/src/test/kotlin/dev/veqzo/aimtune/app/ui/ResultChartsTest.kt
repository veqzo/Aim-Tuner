package dev.veqzo.aimtune.app.ui

import dev.veqzo.aimtune.app.blind.ScoredRound
import dev.veqzo.aimtune.core.accel.GainPoint
import dev.veqzo.aimtune.core.optimizer.CurvePoint
import dev.veqzo.aimtune.core.optimizer.GpHyperParams
import dev.veqzo.aimtune.core.optimizer.OptimizationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResultChartsTest {
    private val tokens = Tokens.load()

    private fun result() = OptimizationResult(
        optimumCm360 = 44.0, atRangeEdge = false, driftDetected = false, intervalLowCm360 = 40.0, intervalHighCm360 = 48.0, confidencePercent = 70,
        negligibleDifference = 1.0, expectedRegret = 0.5, maxExpectedImprovement = 0.2, recommendMoreRounds = true, roundsToTarget = 5,
        curve = (0..10).map { CurvePoint(30.0 + it * 3.0, 50.0 + it, 4.0) },
        hyper = GpHyperParams(1.0, 1.0, 0.3, 0.0, 0.0), rounds = 12, sessions = 2,
    )

    @Test
    fun `the sensitivity chart spans the searched range and marks the optimum`() {
        val rounds = listOf(ScoredRound(35.0, 60.0, 1), ScoredRound(40.0, null, 1), ScoredRound(50.0, 70.0, 2))
        val spec = sensitivityChart(tokens, result(), rounds)
        assertEquals(30.0, spec.xMin)
        assertEquals(60.0, spec.xMax)
        assertEquals(0.0, spec.yMin)
        assertEquals(100.0, spec.yMax)
        assertEquals(44.0, spec.markers.single().x)
        assertEquals(2, spec.dots.single().xs.size, "rounds without a score are not plotted")
        val band = spec.bands.single()
        assertTrue(band.lo.indices.all { band.lo[it] < spec.lines.single().ys[it] && spec.lines.single().ys[it] < band.hi[it] })
    }

    @Test
    fun `the acceleration chart shows the wanted gain and the fitted curve on one scale`() {
        val points = listOf(GainPoint(2.0, -0.05, 0.01, 30), GainPoint(4.0, 0.0, 0.01, 30), GainPoint(8.0, 0.06, 0.02, 30))
        val spec = accelChart(tokens, points, null)
        assertEquals(2.0, spec.xMin)
        assertEquals(8.0, spec.xMax)
        assertTrue(spec.lines.isEmpty())
        assertTrue(spec.yMin < points.minOf { it.gain } && spec.yMax > points.maxOf { it.gain })
        assertEquals(listOf(1.0), spec.referenceYs)
    }

    @Test
    fun `an acceleration chart needs at least two bins`() {
        assertFailsWith<IllegalArgumentException> { accelChart(tokens, listOf(GainPoint(2.0, 0.0, 0.1, 30)), null) }
    }
}
