package de.yuuto.aimtune.core.accel

import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.DuelScorer
import de.yuuto.aimtune.core.score.Engagement
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.RoundScorer
import de.yuuto.aimtune.core.score.ScoreMetric
import de.yuuto.aimtune.core.score.normalCdf
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The two conditions of the blind A/B test: plain sensitivity, or the fitted curve with its sensitivity factor. [PLAIN] is
 * the control arm: in "Refine my Raw Accel curve" it plays the player's imported profile instead of plain sensitivity
 * ([RefineEngine]), and [ACCEL] the refined one; plan and statistics are the same.
 */
enum class AbCondition { PLAIN, ACCEL }

/** One round of the A/B test; both rounds of a pair play the same scenario with the same [seed], so the targets are identical. */
data class AbRound(val pair: Int, val condition: AbCondition, val scenario: ScenarioKind, val seed: Long)

/**
 * The order of a blind A/B test: [maxPairs] pairs of rounds, the scenarios in turn, a fresh seed per
 * pair. Which condition comes first alternates over groups of two pairs (plain-curve, curve-plain)
 * and each group's pattern is chosen by the seed, so neither condition is always first and a slow
 * learning effect hits both about equally. Deterministic for a seed.
 */
class AccelAbPlan(private val scenarios: List<ScenarioKind>, private val seed: Long, val maxPairs: Int = DEFAULT_MAX_PAIRS) {
    init {
        require(scenarios.isNotEmpty()) { "at least one scenario is needed" }
        require(maxPairs >= 2) { "a test needs at least two pairs" }
    }

    private val groupSwapped = List((maxPairs + 1) / 2) { Random(seed xor (it + 1L) * GROUP_MIX).nextBoolean() }

    /** The round with this index (two per pair, starting at 0). */
    fun round(index: Int): AbRound {
        require(index in 0 until 2 * maxPairs) { "round $index is outside the plan" }
        val pair = index / 2
        val second = index % 2 == 1
        val plainFirst = groupSwapped[pair / 2] xor (pair % 2 == 1)
        val plain = plainFirst != second
        return AbRound(pair, if (plain) AbCondition.PLAIN else AbCondition.ACCEL, scenarios[pair % scenarios.size], seed * PAIR_MIX + pair)
    }

    companion object {
        const val DEFAULT_MAX_PAIRS = 12
        private val GROUP_MIX = 0x9E3779B97F4A7C15uL.toLong()
        private const val PAIR_MIX = 7_919L
    }
}

/**
 * What one finished round contributes: its condition, the score metrics it produced, the scenario it played and its
 * [engagements] for the duel score (null for tracking or without events).
 */
data class AbOutcome(
    val pair: Int,
    val condition: AbCondition,
    val inputs: Map<ScoreMetric, Double>,
    val scenarioId: String? = null,
    val engagements: List<Engagement>? = null,
)

enum class AbStatus { NEED_MORE_ROUNDS, ACCEL_BETTER, ACCEL_WORSE, NO_DIFFERENCE }

/**
 * The verdict so far: [meanDifference] is the average score of the curve minus the plain score over
 * the complete pairs, [lowerBound] and [upperBound] the limits of its confidence interval at the
 * per-look level of [evaluateAb].
 */
data class AbVerdict(val status: AbStatus, val pairs: Int, val meanDifference: Double, val lowerBound: Double, val upperBound: Double)

/**
 * Decides a blind A/B test from the rounds played so far. All rounds are scored together (the same
 * score as everywhere else), each complete pair gives one difference, and a t interval of the mean
 * difference decides: the curve is better if the lower bound is above 0, worse if the upper bound
 * is below 0 (and, for either, the mean difference is at least [minEffectPoints]), and without a
 * decision after [maxPairs] pairs there is no measurable difference.
 *
 * The test is looked at after every pair, and every look is another chance to be fooled by luck, so
 * each look uses the strict one-sided level [alphaPerLook] (a Pocock-style correction); the level
 * was calibrated on simulated rounds with no real difference (see `AccelPipelineTest`).
 */
fun evaluateAb(
    outcomes: List<AbOutcome>,
    weights: MetricWeights,
    minPairs: Int = DEFAULT_MIN_PAIRS,
    maxPairs: Int = AccelAbPlan.DEFAULT_MAX_PAIRS,
    alphaPerLook: Double = DEFAULT_ALPHA_PER_LOOK,
    minEffectPoints: Double = DEFAULT_MIN_EFFECT_POINTS,
    /**
     * Scores against per-scenario baselines when every outcome names its scenario, as the rest of the app does. Off by
     * default: in 1000 simulated tests with identical arms it raised the false verdicts from 4.7 % to 6.1 % at the same
     * level, and at a level that brings them back to 5 % it confirmed real curves no more often (gate-v.txt, M14 T2).
     */
    groupByScenario: Boolean = false,
    /** The game's duel score ([RoundScorer]); null scores every round by [weights]. */
    duel: DuelScorer? = null,
): AbVerdict {
    val scenarioIds = outcomes.map { it.scenarioId }
    val grouped = groupByScenario && scenarioIds.all { it != null }
    val scores = RoundScorer(weights, duel).scoreRounds(
        outcomes.map { it.inputs },
        if (grouped) scenarioIds.map { checkNotNull(it) } else null,
        outcomes.map { it.engagements },
    )
    val byPair = outcomes.indices.groupBy { outcomes[it].pair }
    val differences = byPair.values.mapNotNull { indices ->
        val plain = indices.firstOrNull { outcomes[it].condition == AbCondition.PLAIN }?.let { scores[it] }
        val accel = indices.firstOrNull { outcomes[it].condition == AbCondition.ACCEL }?.let { scores[it] }
        if (plain == null || accel == null) null else accel - plain
    }
    val n = differences.size
    if (n < 2) return AbVerdict(AbStatus.NEED_MORE_ROUNDS, n, 0.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
    val mean = differences.average()
    val sd = sqrt(differences.sumOf { (it - mean) * (it - mean) } / (n - 1))
    val margin = tQuantile(n - 1, alphaPerLook) * sd / sqrt(n.toDouble())
    val low = mean - margin
    val high = mean + margin
    val status = when {
        n < minPairs -> AbStatus.NEED_MORE_ROUNDS
        low > 0.0 && mean >= minEffectPoints -> AbStatus.ACCEL_BETTER
        high < 0.0 && mean <= -minEffectPoints -> AbStatus.ACCEL_WORSE
        n >= maxPairs -> AbStatus.NO_DIFFERENCE
        else -> AbStatus.NEED_MORE_ROUNDS
    }
    return AbVerdict(status, n, mean, low, high)
}

const val DEFAULT_MIN_PAIRS = 6

/**
 * One-sided level of every look. 300 simulated tests with no difference gave a 'winner' in 12 % of the
 * runs at 0.02 per look and six looks (docs/history/REVIEW.md M3); 0.01 and a minimum number of pairs and effect
 * bring that down (see `ReviewAccelTest`).
 */
const val DEFAULT_ALPHA_PER_LOOK = 0.01

/** A difference smaller than this many score points is not worth a curve, however sure the interval is. */
const val DEFAULT_MIN_EFFECT_POINTS = 2.0

/**
 * The value that Student's t with [degreesOfFreedom] exceeds with probability [upperTail]: the
 * normal quantile (bisection on the normal CDF) plus the Cornish-Fisher correction for the degrees
 * of freedom, accurate to about 1 % from 3 degrees of freedom up.
 */
internal fun tQuantile(degreesOfFreedom: Int, upperTail: Double): Double {
    var low = 0.0
    var high = 10.0
    repeat(60) {
        val mid = (low + high) / 2
        if (1.0 - normalCdf(mid) > upperTail) low = mid else high = mid
    }
    val z = (low + high) / 2
    val df = degreesOfFreedom.toDouble()
    val z3 = z * z * z
    val z5 = z3 * z * z
    val z7 = z5 * z * z
    return z + (z3 + z) / (4 * df) + (5 * z5 + 16 * z3 + 3 * z) / (96 * df * df) + (3 * z7 + 19 * z5 + 17 * z3 - 15 * z) / (384 * df * df * df)
}
