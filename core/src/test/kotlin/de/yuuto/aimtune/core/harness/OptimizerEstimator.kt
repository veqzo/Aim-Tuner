package de.yuuto.aimtune.core.harness

import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.optimizer.Observation
import de.yuuto.aimtune.core.optimizer.OptimizationResult
import de.yuuto.aimtune.core.optimizer.OptimizerConfig
import de.yuuto.aimtune.core.optimizer.SensitivityOptimizer
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.DuelScorer
import de.yuuto.aimtune.core.score.Engagement
import de.yuuto.aimtune.core.score.RoundScorer
import de.yuuto.aimtune.core.score.duelEngagements
import de.yuuto.aimtune.core.score.ScoreMetric
import de.yuuto.aimtune.core.score.scoreInputs
import de.yuuto.aimtune.core.sim.HeadlessRound
import de.yuuto.aimtune.core.sim.SimulatedPlayer
import de.yuuto.aimtune.core.units.SearchRange

private const val UNKNOWN_SCORE = 50.0

/** Result of a simulated blind test: the final analysis and the history that led to it. */
class SimulatedTest(val result: OptimizationResult?, val history: List<Observation>, val cm360s: List<Double>)

/**
 * Plays a blind test against a simulated player the way the app does: the optimizer picks a
 * cm/360, a round is played and analysed, all rounds are re-scored together (the score is relative
 * to the rounds so far), and the optimizer sees the new history. [sessionOf] maps the round index
 * to a session id so multi-session behaviour can be simulated.
 */
fun playBlindTest(
    player: SimulatedPlayer,
    range: SearchRange,
    rounds: Int,
    seed: Long,
    config: OptimizerConfig = OptimizerConfig(),
    roundSeconds: Int = 45,
    sessionOf: (Int) -> Long = { 0L },
    playerFor: (Int) -> SimulatedPlayer = { player },
    scenarioFor: (Int) -> ScenarioKind = { ScenarioKind.FLICK },
    weights: MetricWeights = MetricWeights.DEFAULT,
    geometry: de.yuuto.aimtune.core.scenario.TrainerGeometry? = null,
    /** When set, the suggestion is not a sensitivity: every round is played at [playedCm360] with the curve this function makes of the suggestion. */
    accelOf: ((Double) -> de.yuuto.aimtune.core.accel.AccelModel?)? = null,
    playedCm360: Double? = null,
    /** The game's duel score (M23g4); null scores every round by [weights]. */
    duel: DuelScorer? = null,
): SimulatedTest {
    val optimizer = SensitivityOptimizer(range, seed, config)
    val calculator = RoundScorer(weights, duel)
    val inputs = mutableListOf<Map<ScoreMetric, Double>>()
    val scenarios = mutableListOf<String>()
    val engagements = mutableListOf<List<Engagement>?>()
    val cm360s = mutableListOf<Double>()
    var history = emptyList<Observation>()
    repeat(rounds) { index ->
        val cm360 = optimizer.suggestNext(history).cm360
        val roundSeed = seed * 7_919L + index
        val offset = index * roundSeconds * 1_000_000_000L
        val kind = scenarioFor(index)
        val played = if (accelOf != null) checkNotNull(playedCm360) else cm360
        val recording = HeadlessRound.of(kind, playerFor(index), TEST_DPI, played, roundSeed, roundSeconds, offset, accel = accelOf?.invoke(cm360), geometry = geometry)
        inputs += computeRoundMetrics(recording).scoreInputs()
        engagements += duelEngagements(kind, recording.targetEvents)
        scenarios += kind.id
        cm360s += cm360
        val scores = calculator.scoreRounds(inputs, scenarios, engagements)
        val stepInSession = HashMap<Long, Int>()
        history = cm360s.indices.map {
            val step = checkNotNull(stepInSession.merge(sessionOf(it), 1, Int::plus)) - 1
            Observation(sessionOf(it), cm360s[it], scores[it] ?: UNKNOWN_SCORE, group = scenarios[it].hashCode(), step = step)
        }
    }
    return SimulatedTest(optimizer.analyze(history), history, cm360s)
}

/** The Gaussian process optimizer as a [SensitivityEstimator] with a fixed round budget. */
class OptimizerEstimator(
    private val rounds: Int = 20,
    private val range: SearchRange = SearchRange(20.0, 140.0),
    private val roundSeconds: Int = 45,
) : SensitivityEstimator {
    override fun estimate(player: SimulatedPlayer, seed: Long): Estimate {
        val test = playBlindTest(player, range, rounds, seed, roundSeconds = roundSeconds)
        return Estimate(checkNotNull(test.result).optimumCm360, rounds)
    }
}
