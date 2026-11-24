package dev.veqzo.aimtune.core.score

import dev.veqzo.aimtune.core.score.ScoreMetric.ACCURACY
import dev.veqzo.aimtune.core.score.ScoreMetric.CONSISTENCY
import dev.veqzo.aimtune.core.score.ScoreMetric.TIME_TO_HIT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoreCalculatorTest {
    private val calculator = ScoreCalculator(MetricWeights.DEFAULT)

    private fun round(accuracy: Double, ttkMs: Double, spread: Double? = null) =
        buildMap {
            put(ACCURACY, accuracy)
            put(TIME_TO_HIT, ttkMs)
            if (spread != null) put(CONSISTENCY, spread)
        }

    @Test
    fun `default weights are accuracy 40 time to hit 30 tracking 20 and consistency does not score`() {
        val w = MetricWeights.DEFAULT.normalized(ScoreMetric.entries.toSet())
        assertEquals(40.0 / 90.0, w.getValue(ACCURACY), 1e-12)
        assertEquals(30.0 / 90.0, w.getValue(TIME_TO_HIT), 1e-12)
        assertEquals(20.0 / 90.0, w.getValue(ScoreMetric.TRACKING), 1e-12)
        assertEquals(false, CONSISTENCY in w.keys)
    }

    @Test
    fun `consistency never changes a score and a weight on it alone is refused`() {
        val base = (0 until 6).map { round(accuracy = 0.5 + it * 0.05, ttkMs = 900.0 - it * 20) }
        val withSpread = base.mapIndexed { i, m -> m + (CONSISTENCY to (0.1 + 0.3 * (i % 2))) }
        assertEquals(calculator.scoreRounds(base), calculator.scoreRounds(withSpread))
        assertFailsWith<IllegalArgumentException> { MetricWeights(mapOf(CONSISTENCY to 1.0)) }
    }

    @Test
    fun `weights are renormalized over the available metrics`() {
        val w = MetricWeights.DEFAULT.normalized(setOf(ACCURACY, TIME_TO_HIT))
        assertEquals(4.0 / 7.0, w.getValue(ACCURACY), 1e-12)
        assertEquals(3.0 / 7.0, w.getValue(TIME_TO_HIT), 1e-12)
    }

    @Test
    fun `better accuracy and faster hits score higher`() {
        val rounds = listOf(round(0.6, 700.0), round(0.7, 600.0), round(0.8, 500.0), round(0.9, 400.0))
        val scores = calculator.scoreRounds(rounds).map { assertNotNull(it) }
        assertEquals(scores.sorted(), scores)
        assertTrue(scores.last() > 50 && scores.first() < 50)
    }

    @Test
    fun `the error spread is a diagnostic and does not change the score`() {
        val rounds = listOf(round(0.8, 500.0, 0.30), round(0.7, 600.0, 0.20), round(0.6, 700.0, 0.10))
        val without = rounds.map { it - CONSISTENCY }
        assertEquals(calculator.scoreRounds(without), calculator.scoreRounds(rounds))
    }

    @Test
    fun `a typical round scores near 50 and scores stay inside 0 to 100`() {
        val rounds = listOf(round(0.6, 700.0), round(0.7, 600.0), round(0.8, 500.0))
        val scores = calculator.scoreRounds(rounds).map { assertNotNull(it) }
        assertEquals(50.0, scores[1], 0.5)
        assertTrue(scores.all { it in 0.0..100.0 })
    }

    @Test
    fun `too few rounds give no score`() {
        assertTrue(calculator.scoreRounds(listOf(round(0.8, 500.0), round(0.7, 600.0))).all { it == null })
    }

    @Test
    fun `a metric without variation is ignored instead of dividing by zero`() {
        val rounds = listOf(round(0.8, 700.0), round(0.8, 600.0), round(0.8, 500.0))
        val scores = calculator.scoreRounds(rounds).map { assertNotNull(it) }
        assertTrue(scores[2] > scores[0])
    }

    @Test
    fun `rounds missing a metric are scored on the others`() {
        val rounds = listOf(round(0.6, 700.0, 0.3), round(0.7, 600.0, 0.2), round(0.8, 500.0, 0.1), round(0.9, 400.0))
        val scores = calculator.scoreRounds(rounds)
        assertNotNull(scores.last())
        assertNull(ScoreCalculator(MetricWeights.DEFAULT).scoreRounds(listOf(emptyMap(), emptyMap(), emptyMap())).firstOrNull())
    }

    @Test
    fun `every scenario is scored against its own rounds`() {
        // Micro rounds are all much easier than flick rounds; pooled, every micro round would look excellent.
        val flick = listOf(round(0.60, 900.0), round(0.70, 800.0), round(0.80, 700.0))
        val micro = listOf(round(0.90, 400.0), round(0.95, 350.0), round(0.99, 300.0))
        val rounds = flick + micro
        val scenarios = List(3) { "flick" } + List(3) { "micro" }

        val pooled = calculator.scoreRounds(rounds).map { assertNotNull(it) }
        val separate = calculator.scoreRounds(rounds, scenarios).map { assertNotNull(it) }

        assertTrue(pooled.take(3).all { it < pooled[3] }, "pooled, the easy scenario dominates: $pooled")
        assertTrue(separate[0] < 50 && separate[2] > 50 && separate[3] < 50 && separate[5] > 50, "separate: $separate")
    }

    @Test
    fun `a scenario with too few rounds has no score yet while others do`() {
        val rounds = List(3) { round(0.6 + it * 0.1, 700.0 - it * 100) } + List(2) { round(0.9, 300.0 + it) }
        val scores = calculator.scoreRounds(rounds, List(3) { "flick" } + List(2) { "micro" })
        assertTrue(scores.take(3).all { it != null } && scores.drop(3).all { it == null })
    }

    @Test
    fun `tracking rounds are scored on tracking alone`() {
        val tracking = listOf(0.30, 0.40, 0.50).map { mapOf(ScoreMetric.TRACKING to it) }
        val scores = calculator.scoreRounds(tracking, List(3) { "tracking" }).map { assertNotNull(it) }
        assertTrue(scores[0] < scores[1] && scores[1] < scores[2])
        assertEquals(50.0, scores[1], 0.5)
    }

    @Test
    fun `normal cdf matches known values`() {
        assertEquals(0.5, normalCdf(0.0), 1e-7)
        assertEquals(0.97500, normalCdf(1.96), 1e-4)
        assertEquals(0.15866, normalCdf(-1.0), 1e-4)
        assertEquals(1.0, normalCdf(8.0), 1e-7)
    }

    @Test
    fun `invalid weights are rejected`() {
        assertFailsWith<IllegalArgumentException> { MetricWeights(mapOf(ACCURACY to -1.0)) }
        assertFailsWith<IllegalArgumentException> { MetricWeights(mapOf(ACCURACY to 0.0)) }
    }
}
