package dev.veqzo.aimtune.core.sim

import dev.veqzo.aimtune.core.accel.AccelAnalysis
import dev.veqzo.aimtune.core.accel.AccelProcessor
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Player-model options: decoupled endpoint noise and the range effect. */
class PlayerModelsTest {
    private val kinds = listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO)
    private val seeds = listOf(101L, 102L, 103L)
    private val roundsPerSeed = 6

    /** One usable first movement: distance (deg), signed error e, peak speed (in/s). */
    private data class Mv(val kind: ScenarioKind, val dist: Double, val err: Double, val speed: Double) {
        val y: Double get() = ln(1.0 + err)
    }

    /** Pooled first movements of plain calibration rounds over all seeds, filtered like AccelAnalysis. */
    private fun movements(player: SimulatedPlayer): List<Mv> = seeds.flatMap { seed ->
        (0 until roundsPerSeed).flatMap { i ->
            val kind = kinds[i % kinds.size]
            val round = HeadlessRound.of(kind, player, TEST_DPI, 55.0, seed * 1_000 + i, 45)
            computeRoundMetrics(round).movements.mapNotNull { m ->
                val peak = m.firstPeakSpeedCountsPerMs ?: return@mapNotNull null
                val e = m.firstErrorRatio ?: return@mapNotNull null
                Mv(kind, m.initialDistanceDegrees, e, AccelProcessor.speedInPerSecond(peak, TEST_DPI))
            }
        }
    }.filter { it.speed >= AccelAnalysis.MIN_SPEED && (1.0 + it.err) in AccelAnalysis.MIN_RATIO..AccelAnalysis.MAX_RATIO }

    private fun pearson(x: List<Double>, y: List<Double>): Double {
        val mx = x.average()
        val my = y.average()
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        val syy = y.sumOf { (it - my) * (it - my) }
        if (sxx == 0.0 || syy == 0.0) return Double.NaN
        return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / sqrt(sxx * syy)
    }

    /**
     * Mean of r(ln(1+e), ln speed) over the distance deciles of each scenario. Per scenario, because the scenarios differ
     * in target width (so in speed for a distance) and in their error bias, which is not the coupling looked for here.
     */
    private fun withinDistanceCorrelation(items: List<Mv>): Double {
        assertTrue(items.size >= 200, "too few movements: ${items.size}")
        val rs = items.groupBy { it.kind }.values.filter { it.size >= 100 }.flatMap { group ->
            val sorted = group.sortedBy { it.dist }
            (0 until 10).map { d ->
                val slice = sorted.subList(d * sorted.size / 10, (d + 1) * sorted.size / 10)
                pearson(slice.map { it.y }, slice.map { ln(it.speed) })
            }
        }
        return rs.average()
    }

    private fun sd(values: List<Double>): Double {
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /** Median e of the shortest distance quintile minus that of the longest quintile. */
    private fun rangeEffect(items: List<Mv>): Double {
        assertTrue(items.size >= 100, "too few movements: ${items.size}")
        val sorted = items.sortedBy { it.dist }
        val k = sorted.size / 5
        return median(sorted.take(k).map { it.err }) - median(sorted.takeLast(k).map { it.err })
    }

    @Test
    fun `default player keeps the error coupled to the peak speed within a distance`() {
        val r = withinDistanceCorrelation(movements(SimulatedPlayer()))
        assertTrue(r > 0.25, "mean within-distance r was $r")
    }

    @Test
    fun `decoupled noise removes the coupling within a distance`() {
        val r = withinDistanceCorrelation(movements(SimulatedPlayer(noiseModel = NoiseModel.DECOUPLED)))
        // About 0.06 is left (the default has about 0.36): within a decile the distance still varies a little.
        assertTrue(abs(r) < 0.1, "mean within-distance r was $r")
    }

    @Test
    fun `decoupled noise keeps the endpoint spread`() {
        val default = sd(movements(SimulatedPlayer()).map { it.y })
        val decoupled = sd(movements(SimulatedPlayer(noiseModel = NoiseModel.DECOUPLED)).map { it.y })
        assertTrue(abs(decoupled / default - 1.0) <= 0.15, "SD default $default, decoupled $decoupled")
    }

    @Test
    fun `range bias overshoots short and undershoots long reaches`() {
        val biased = rangeEffect(movements(SimulatedPlayer(rangeBias = 0.05)))
        val default = rangeEffect(movements(SimulatedPlayer()))
        assertTrue(biased > 0.04, "biased short-minus-long median e was $biased")
        assertTrue(biased - default >= 0.03, "biased $biased, default $default")
    }

    @Test
    fun `defaults leave the player unchanged`() {
        assertEquals(SimulatedPlayer(), SimulatedPlayer(noiseModel = NoiseModel.PLANNED, rangeBias = 0.0))
        val a = HeadlessRound.flick(SimulatedPlayer(), TEST_DPI, 55.0, 7L)
        val b = HeadlessRound.flick(SimulatedPlayer(noiseModel = NoiseModel.PLANNED, rangeBias = 0.0), TEST_DPI, 55.0, 7L)
        assertEquals(a.mouseEvents, b.mouseEvents)
        assertEquals(a.targetEvents, b.targetEvents)
    }
}
