package de.yuuto.aimtune.core.harness

import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.DuelScorer
import de.yuuto.aimtune.core.score.RoundScorer
import de.yuuto.aimtune.core.score.duelEngagements
import de.yuuto.aimtune.core.score.scoreInputs
import de.yuuto.aimtune.core.sim.HeadlessRound
import de.yuuto.aimtune.core.sim.SimulatedPlayer
import kotlin.math.exp
import kotlin.math.ln

const val TEST_DPI = 800.0

/** Geometric grid from [lo] to [hi] with [count] points. */
fun geometricGrid(lo: Double, hi: Double, count: Int): List<Double> =
    List(count) { lo * Math.pow(hi / lo, it.toDouble() / (count - 1)) }

/**
 * Plays [roundsEach] simulated rounds at every cm/360 of [grid] through segmentation, metrics and
 * the score, and returns the mean score per grid point. Scores are normalized over all rounds
 * pooled, as the app does over a user's rounds.
 */
fun meanScores(
    player: SimulatedPlayer,
    grid: List<Double>,
    roundsEach: Int,
    seed: Long,
    roundSeconds: Int = 45,
    kind: ScenarioKind = ScenarioKind.FLICK,
    /** The game's duel score (M23g4); null scores by the default weights. */
    duel: DuelScorer? = null,
): List<Double> {
    val recordings = grid.flatMapIndexed { gi, cm ->
        List(roundsEach) { r ->
            val roundSeed = seed * 1_000_003L + gi * 1_009L + r
            val offset = (gi * roundsEach + r) * roundSeconds * 1_000_000_000L
            HeadlessRound.of(kind, player, TEST_DPI, cm, roundSeed, roundSeconds, offset)
        }
    }
    val scores = RoundScorer(MetricWeights.DEFAULT, duel).scoreRounds(
        recordings.map { computeRoundMetrics(it).scoreInputs() }, null, recordings.map { duelEngagements(kind, it.targetEvents) },
    )
    return grid.indices.map { gi -> (0 until roundsEach).mapNotNull { scores[gi * roundsEach + it] }.average() }
}

/** Vertex of the parabola through the best grid point and its neighbours, in log space; the grid edge if the best point is one. */
fun parabolicOptimum(grid: List<Double>, means: List<Double>): Double {
    val best = means.indices.maxBy { means[it] }
    if (best == 0 || best == grid.lastIndex) return grid[best]
    val x = (best - 1..best + 1).map { ln(grid[it]) }
    val y = (best - 1..best + 1).map { means[it] }
    val d1 = (y[1] - y[0]) / (x[1] - x[0])
    val d2 = (y[2] - y[1]) / (x[2] - x[1])
    val curvature = (d2 - d1) / (x[2] - x[0])
    if (curvature >= 0.0) return grid[best]
    val vertex = (x[0] + x[1]) / 2.0 - d1 / (2.0 * curvature) + 0.0
    return exp(vertex.coerceIn(x[0], x[2]))
}
