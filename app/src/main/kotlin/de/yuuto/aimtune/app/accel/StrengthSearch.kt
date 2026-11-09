package de.yuuto.aimtune.app.accel

import de.yuuto.aimtune.core.accel.CurveStrength
import de.yuuto.aimtune.core.metrics.RoundMetrics
import de.yuuto.aimtune.core.optimizer.Observation
import de.yuuto.aimtune.core.optimizer.SensitivityOptimizer
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.DuelScorer
import de.yuuto.aimtune.core.score.Engagement
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.RoundScorer
import de.yuuto.aimtune.core.score.ScoreMetric
import de.yuuto.aimtune.core.score.scoreInputs

/** What the blind strength search found. [low]..[high] is the credible range of the best strength. */
data class StrengthResult(
    val strength: Double,
    val low: Double,
    val high: Double,
    val confidencePercent: Int,
    val rounds: Int,
    /** True when the credible range clearly excludes "no curve" ([CurveStrength.excludesPlain]: above 1, or below the falling limit). */
    val excludesPlain: Boolean,
    /** True when the best strength is at an end of the searched range, so a stronger curve may do better. */
    val atEdge: Boolean,
    /** The searched range of strengths; it is wider when the search played flash rounds. */
    val rangeMin: Double = CurveStrength.MIN,
    val rangeMax: Double = CurveStrength.MAX,
)

/**
 * A round of an earlier search, as the optimizer needs it: the strength it was played with, its score inputs and where it
 * was played. [sessionKey] says which earlier session it belongs to (a better or worse day is learned per session) and
 * [step] is its position within that session. [engagements] are its duels for the duel score (null for tracking or without events).
 */
data class PriorSearchRound(
    val sessionKey: Long,
    val step: Int,
    val strength: Double,
    val scenarioId: String,
    val scoreInputs: Map<ScoreMetric, Double>,
    val engagements: List<Engagement>? = null,
)

/**
 * The blind search for the curve strength: rounds played with different strengths (some of them no curve
 * at all) at the recommended sensitivity, scored like every other round, and the same optimizer that
 * finds the sensitivity looks for the strength that scores best. Without UI; [AccelRun] drives it.
 * [prior] holds the rounds of earlier searches at the same setup; they count for the optimizer like the rounds of
 * this search, so [plannedRounds] can be lower and the first strengths already build on what is known.
 */
class StrengthSearch(
    weights: MetricWeights,
    seed: Long,
    val plannedRounds: Int = DEFAULT_ROUNDS,
    private val prior: List<PriorSearchRound> = emptyList(),
    /** Whether the search plays flash rounds, which widens its strength range (`CurveStrength.searchRange`). */
    flash: Boolean = false,
    /** The player's own curve the strengths scale, or null for the natural curves over a plain mouse; it sets the range. */
    base: SearchBase? = null,
    /** The game's duel score ([RoundScorer]); null scores every round by [weights]. */
    duel: DuelScorer? = null,
) {
    private val scorer = RoundScorer(weights, duel)
    // Earlier rounds above the narrow range (a search that played flash rounds) widen it too, so the optimizer covers them.
    private val range = base?.strengths?.searchRange
        ?: CurveStrength.searchRange(flash || prior.any { it.strength > CurveStrength.MAX + STRENGTH_TOLERANCE })
    private val optimizer = SensitivityOptimizer(range, seed)
    private val inputs = mutableListOf<Map<ScoreMetric, Double>>()
    private val engagements = mutableListOf<List<Engagement>?>()
    private val scenarios = mutableListOf<String>()
    private val pseudo = mutableListOf<Double>()

    /** Rounds of this search, without the earlier ones. */
    val roundsPlayed: Int get() = inputs.size

    val priorRounds: Int get() = prior.size

    /** From this many own rounds on the search may end early: 16 in all, but a day with earlier rounds still plays a few new ones. */
    val minRounds: Int get() = minOf(plannedRounds, maxOf(MIN_NEW_ROUNDS, MIN_ROUNDS - prior.size))

    /** The strength of the next round. Blind: callers must not show it. */
    fun nextStrength(): Double = CurveStrength.fromPseudo(optimizer.suggestNext(history()).cm360)

    fun record(strength: Double, scenario: ScenarioKind, metrics: RoundMetrics, roundEngagements: List<Engagement>? = null) =
        record(strength, scenario.id, metrics.scoreInputs(), roundEngagements)

    /** Takes a round by its scenario id, score inputs and duels ([PriorSearchRound.engagements]). */
    fun record(strength: Double, scenarioId: String, scoreInputs: Map<ScoreMetric, Double>, roundEngagements: List<Engagement>? = null) {
        inputs += scoreInputs
        engagements += roundEngagements
        scenarios += scenarioId
        pseudo += CurveStrength.toPseudo(strength)
    }

    /** The result so far, or null while there are too few rounds. */
    fun result(): StrengthResult? {
        val analysis = optimizer.analyze(history()) ?: return null
        val strength = CurveStrength.fromPseudo(analysis.optimumCm360)
        val low = CurveStrength.fromPseudo(analysis.intervalLowCm360)
        val high = CurveStrength.fromPseudo(analysis.intervalHighCm360)
        return StrengthResult(
            strength, low, high, analysis.confidencePercent, prior.size + roundsPlayed, CurveStrength.excludesPlain(strength, low, high), analysis.atRangeEdge,
            CurveStrength.fromPseudo(range.minCm360), CurveStrength.fromPseudo(range.maxCm360),
        )
    }

    private fun history(): List<Observation> {
        val scores = scorer.scoreRounds(prior.map { it.scoreInputs } + inputs, prior.map { it.scenarioId } + scenarios, prior.map { it.engagements } + engagements)
        val earlier = prior.indices.map {
            val round = prior[it]
            Observation(round.sessionKey, CurveStrength.toPseudo(round.strength), scores[it] ?: UNKNOWN_SCORE, round.scenarioId.hashCode(), round.step)
        }
        val own = pseudo.indices.map { Observation(OWN_SESSION, pseudo[it], scores[prior.size + it] ?: UNKNOWN_SCORE, scenarios[it].hashCode(), it) }
        return earlier + own
    }

    companion object {
        /** The experiments used 24 rounds (docs/history/ASSESSMENT.md); the user may finish from [MIN_ROUNDS] on. */
        const val DEFAULT_ROUNDS = 24
        const val MIN_ROUNDS = 16

        /** With earlier rounds to build on, a search still plays at least this many new rounds, and may end after [MIN_NEW_ROUNDS]. */
        const val MIN_NEW_ROUNDS = 6
        const val MIN_PLANNED_WITH_PRIOR = 8

        /** The session id of this search's own rounds; stored sessions have positive ids. */
        private const val OWN_SESSION = 0L

        private const val STRENGTH_TOLERANCE = 1e-9

        /** A round without a usable score yet only counts for the design phase. */
        private const val UNKNOWN_SCORE = 50.0

        /** The rounds to plan for a search that has [priorRounds] earlier rounds to build on. */
        fun plannedFor(priorRounds: Int): Int = if (priorRounds == 0) DEFAULT_ROUNDS else maxOf(MIN_PLANNED_WITH_PRIOR, DEFAULT_ROUNDS - priorRounds)
    }
}
