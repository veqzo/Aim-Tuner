package de.yuuto.aimtune.core.accel

import de.yuuto.aimtune.core.score.DuelScorer
import de.yuuto.aimtune.core.score.Engagement
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.ScoreMetric
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccelAbDuelTest {
    private val weights = MetricWeights(mapOf(ScoreMetric.TIME_TO_HIT to 0.5, ScoreMetric.ACCURACY to 0.5))

    private fun jitter(seed: Int) = sin(seed * 12.9898) // deterministic, in [-1, 1]

    /** Sixteen reaches around [meanMs] that all hit with the first shot. */
    private fun duels(meanMs: Double, seed: Int): List<Engagement> =
        (0 until 16).map { Engagement(((meanMs + 30.0 * jitter(seed * 16 + it)) * 1e6).toLong(), firstShotHit = true, lost = false) }

    /** The metric inputs of both arms are the same; only the duels differ: the curve arm shoots 40 ms sooner. */
    private fun outcomes(pairs: Int): List<AbOutcome> = (0 until pairs).flatMap { pair ->
        val inputs = mapOf(ScoreMetric.TIME_TO_HIT to 500.0 + jitter(pair), ScoreMetric.ACCURACY to 0.8)
        listOf(
            AbOutcome(pair, AbCondition.PLAIN, inputs, "flick", duels(400.0, 2 * pair)),
            AbOutcome(pair, AbCondition.ACCEL, inputs, "flick", duels(360.0, 2 * pair + 1)),
        )
    }

    @Test
    fun `with duel rules the test decides by the duels, without them by the metrics`() {
        val rounds = outcomes(10)
        val duel = evaluateAb(rounds, weights, duel = DuelScorer())
        assertEquals(AbStatus.ACCEL_BETTER, duel.status, "faster first shots win more duels: $duel")
        val metric = evaluateAb(rounds, weights)
        assertTrue(metric.status != AbStatus.ACCEL_BETTER, "the metrics of both arms are the same: $metric")
    }
}
