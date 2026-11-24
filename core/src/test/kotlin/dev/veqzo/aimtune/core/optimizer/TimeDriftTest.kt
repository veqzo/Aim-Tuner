package dev.veqzo.aimtune.core.optimizer

import dev.veqzo.aimtune.core.units.SearchRange
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeDriftTest {
    private val range = SearchRange(20.0, 140.0)

    private fun gauss(r: Random) = sqrt(-2.0 * ln(1.0 - r.nextDouble())) * cos(2.0 * Math.PI * r.nextDouble())

    /** Sessions on days 0, 7, 14, ...; the true best cm/360 of session s is [optima][s]. */
    private fun history(optima: List<Double>, daysApart: Double = 7.0, rounds: Int = 12, seed: Long = 5L, firstSession: Long = 0L): List<Observation> {
        val r = Random(seed)
        return optima.indices.flatMap { s ->
            (0 until rounds).map { i ->
                val cm = exp(ln(range.minCm360) + r.nextDouble() * (ln(range.maxCm360) - ln(range.minCm360)))
                val z = (ln(cm) - ln(optima[s])) / 0.3
                val score = -z * z * 10.0 + 1.5 * gauss(r)
                Observation(firstSession + s, cm, score, step = i, days = daysApart * s)
            }
        }
    }

    private fun analyze(obs: List<Observation>, config: OptimizerConfig) = checkNotNull(SensitivityOptimizer(range, 1L, config).analyze(obs))

    @Test
    fun timeDriftOffIgnoresDays() {
        val withDays = history(listOf(40.0, 44.0, 48.0, 52.0))
        val noDays = withDays.map { it.copy(days = 0.0) }
        val a = analyze(withDays, OptimizerConfig())
        val b = analyze(withDays, OptimizerConfig(timeDrift = false))
        val c = analyze(noDays, OptimizerConfig())
        assertEquals(a, b)
        assertEquals(a, c)
        assertTrue(!a.dayDriftDetected)
    }

    @Test
    fun zeroDriftShareIgnoresDays() {
        val withDays = history(listOf(40.0, 44.0, 48.0, 52.0))
        val noDays = withDays.map { it.copy(days = 0.0) }
        val hyper = GpHyperParams(0.3 * ln(range.maxCm360 / range.minCm360), 1.0, 0.3, 0.1)
        val grid = DoubleArray(40) { ln(20.0) + (ln(140.0) - ln(20.0)) * it / 39 }
        val p1 = checkNotNull(GaussianProcess.fit(withDays, hyper)).posterior(grid)
        val p2 = checkNotNull(GaussianProcess.fit(noDays, hyper)).posterior(grid)
        for (i in grid.indices) assertEquals(p2.mean[i], p1.mean[i], 1e-12)
    }

    @Test
    fun movingOptimumIsFollowed() {
        val h = history(listOf(40.0, 44.0, 48.0, 52.0))
        val legacy = analyze(h, OptimizerConfig(timeDrift = false))
        val timed = analyze(h, OptimizerConfig(timeDrift = true))
        assertTrue(abs(timed.optimumCm360 - 52.0) < abs(legacy.optimumCm360 - 52.0), "timed ${timed.optimumCm360} legacy ${legacy.optimumCm360}")
        assertTrue(timed.dayDriftDetected)
    }

    @Test
    fun constantOptimumStaysPut() {
        val h = history(listOf(45.0, 45.0, 45.0, 45.0))
        val legacy = analyze(h, OptimizerConfig(timeDrift = false))
        val timed = analyze(h, OptimizerConfig(timeDrift = true))
        assertTrue(abs(timed.optimumCm360 / 45.0 - 1.0) < 0.08, "timed ${timed.optimumCm360}")
        assertTrue(abs(timed.optimumCm360 / legacy.optimumCm360 - 1.0) < 0.05, "timed ${timed.optimumCm360} legacy ${legacy.optimumCm360}")
    }

    @Test
    fun roundsOlderThanMaxAgeAreIgnored() {
        val recent = history(listOf(45.0, 45.0), daysApart = 7.0, seed = 9L, firstSession = 10L).map { it.copy(days = it.days + 100.0) }
        val old = history(listOf(100.0, 100.0), daysApart = 7.0, seed = 11L).map { it.copy(days = it.days) }
        val config = OptimizerConfig(timeDrift = true, maxAgeDays = 42.0)
        val onlyRecent = analyze(recent, config)
        val both = analyze(old + recent, config)
        assertEquals(onlyRecent, both)
        val unlimited = analyze(old + recent, OptimizerConfig(timeDrift = true, maxAgeDays = 1000.0))
    }
}
