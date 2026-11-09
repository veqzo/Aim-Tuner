package de.yuuto.aimtune.core.accel

import de.yuuto.aimtune.core.optimizer.Observation
import de.yuuto.aimtune.core.optimizer.SensitivityOptimizer
import de.yuuto.aimtune.core.score.DuelScorer
import de.yuuto.aimtune.core.score.Engagement
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.RoundScorer
import de.yuuto.aimtune.core.score.ScoreMetric

/** What one stage of the refine search found: the best factor of [parameter] with its credible range. 1 is the imported value. */
data class RefineResult(
    val parameter: RefineParameter,
    val best: Double,
    val low: Double,
    val high: Double,
    val confidencePercent: Int,
    val rounds: Int,
    /** True when the credible range clearly excludes 1 ([RefineParameter.excludesNeutral]); only then the stage changes the curve. */
    val excludesNeutral: Boolean,
    /** True when the best factor is at an end of the searched range, so a bigger change may do better. */
    val atEdge: Boolean,
) {
    /** The factor the next stage starts from: [best] if it is a clear change, else 1. */
    val carried: Double get() = if (excludesNeutral) best else RefineParameter.NEUTRAL
}

/**
 * The blind search of one [RefineParameter]: rounds played with different factors of it, scored like every other round, and the
 * same optimizer that finds the sensitivity looks for the factor that scores best, over a pseudo cm/360 of `100 * factor`
 * (log scale, like [CurveStrength]). Without UI; [RefineEngine] drives it.
 */
class RefineSearch(
    val parameter: RefineParameter,
    weights: MetricWeights,
    seed: Long,
    val plannedRounds: Int = DEFAULT_ROUNDS,
    /** The game's duel score ([RoundScorer]); null scores every round by [weights]. */
    duel: DuelScorer? = null,
) {
    private val scorer = RoundScorer(weights, duel)
    private val optimizer = SensitivityOptimizer(parameter.searchRange, seed)
    private val inputs = mutableListOf<Map<ScoreMetric, Double>>()
    private val engagements = mutableListOf<List<Engagement>?>()
    private val scenarios = mutableListOf<String>()
    private val pseudo = mutableListOf<Double>()

    init {
        require(plannedRounds >= MIN_ROUNDS) { "a stage needs at least $MIN_ROUNDS rounds" }
    }

    val roundsPlayed: Int get() = inputs.size

    /** From this many rounds on the stage may end early. */
    val minRounds: Int get() = minOf(plannedRounds, MIN_ROUNDS)

    /** The factor of the next round. Blind: callers must not show it. */
    fun nextValue(): Double = parameter.fromPseudo(optimizer.suggestNext(history()).cm360)

    /** Takes a round played at [value]; [roundEngagements] are its duels for the duel score (null for tracking or without events). */
    fun record(value: Double, scenarioId: String, scoreInputs: Map<ScoreMetric, Double>, roundEngagements: List<Engagement>? = null) {
        inputs += scoreInputs
        engagements += roundEngagements
        scenarios += scenarioId
        pseudo += parameter.toPseudo(value)
    }

    /** The result so far, or null while there are too few rounds. */
    fun result(): RefineResult? {
        val analysis = optimizer.analyze(history()) ?: return null
        val best = parameter.fromPseudo(analysis.optimumCm360)
        val low = parameter.fromPseudo(analysis.intervalLowCm360)
        val high = parameter.fromPseudo(analysis.intervalHighCm360)
        return RefineResult(parameter, best, low, high, analysis.confidencePercent, roundsPlayed, parameter.excludesNeutral(best, low, high), analysis.atRangeEdge)
    }

    private fun history(): List<Observation> {
        val scores = scorer.scoreRounds(inputs, scenarios, engagements)
        return pseudo.indices.map { Observation(SESSION, pseudo[it], scores[it] ?: UNKNOWN_SCORE, scenarios[it].hashCode(), it) }
    }

    companion object {
        /** Twelve rounds per stage; the player may end a stage from [MIN_ROUNDS] on. */
        const val DEFAULT_ROUNDS = 12
        const val MIN_ROUNDS = 8
        private const val SESSION = 0L

        /** A round without a usable score yet only counts for the design phase. */
        private const val UNKNOWN_SCORE = 50.0
    }
}
