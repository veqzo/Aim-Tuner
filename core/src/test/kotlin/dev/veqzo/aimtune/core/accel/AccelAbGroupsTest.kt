package dev.veqzo.aimtune.core.accel

import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreMetric
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccelAbGroupsTest {
    private val weights = MetricWeights(mapOf(ScoreMetric.TIME_TO_HIT to 0.5, ScoreMetric.ACCURACY to 0.5))

    private fun jitter(seed: Int) = sin(seed * 12.9898) // deterministic, in [-1, 1]

    private fun inputs(time: Double, accuracy: Double) =
        mapOf(ScoreMetric.TIME_TO_HIT to time, ScoreMetric.ACCURACY to accuracy)

    /** Pairs alternate "flick" and "micro"; the curve round is 3 % faster and 0.02 more accurate. */
    private fun outcomes(pairs: Int, withScenarioIds: Boolean): List<AbOutcome> = (0 until pairs).flatMap { pair ->
        val flick = pair % 2 == 0
        val id = if (withScenarioIds) (if (flick) "flick" else "micro") else null
        val baseTime = if (flick) 900.0 else 400.0
        val baseAccuracy = if (flick) 0.6 else 0.9
        val time = baseTime * (1 + 0.02 * jitter(pair))
        val accuracy = baseAccuracy + 0.01 * jitter(pair + 100)
        listOf(
            AbOutcome(pair, AbCondition.PLAIN, inputs(time * (1 + 0.005 * jitter(2 * pair + 200)), accuracy + 0.003 * jitter(2 * pair + 300)), id),
            AbOutcome(pair, AbCondition.ACCEL, inputs(time * 0.97 * (1 + 0.005 * jitter(2 * pair + 201)), accuracy + 0.02 + 0.003 * jitter(2 * pair + 301)), id),
        )
    }

    @Test
    fun `per-scenario baselines give a larger mean difference than pooled ones`() {
        val rounds = outcomes(12, withScenarioIds = true)
        val grouped = evaluateAb(rounds, weights, groupByScenario = true)
        val pooled = evaluateAb(rounds, weights, groupByScenario = false)
        assertTrue(grouped.meanDifference > pooled.meanDifference, "grouped ${grouped.meanDifference} vs pooled ${pooled.meanDifference}")
    }

    @Test
    fun `grouped scoring decides for the curve after twelve pairs`() {
        val verdict = evaluateAb(outcomes(12, withScenarioIds = true), weights, groupByScenario = true)
        assertEquals(AbStatus.ACCEL_BETTER, verdict.status)
    }

    @Test
    fun `without scenario ids grouping changes nothing`() {
        val rounds = outcomes(12, withScenarioIds = false)
        assertEquals(evaluateAb(rounds, weights, groupByScenario = false), evaluateAb(rounds, weights, groupByScenario = true))
    }

    @Test
    fun `one outcome without a scenario id turns the grouping off for all`() {
        val grouped = outcomes(12, withScenarioIds = true)
        val mixed = grouped.mapIndexed { i, it -> if (i == 0) it.copy(scenarioId = null) else it }
        assertEquals(evaluateAb(mixed, MetricWeights.DEFAULT, groupByScenario = false), evaluateAb(mixed, MetricWeights.DEFAULT, groupByScenario = true))
    }

    @Test
    fun `the default scores pooled, as before`() {
        val rounds = outcomes(12, withScenarioIds = true)
        assertEquals(evaluateAb(rounds, weights, groupByScenario = false), evaluateAb(rounds, weights))
    }

    @Test
    fun `identical rounds in both conditions give a grouped difference of zero`() {
        val same = outcomes(12, withScenarioIds = true).map { it.copy(inputs = outcomes(12, true).first { o -> o.pair == it.pair && o.condition == AbCondition.PLAIN }.inputs) }
        val verdict = evaluateAb(same, weights, groupByScenario = true)
        assertEquals(0.0, verdict.meanDifference, 1e-9)
    }
}
