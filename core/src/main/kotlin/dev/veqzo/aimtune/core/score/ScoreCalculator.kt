package dev.veqzo.aimtune.core.score

import dev.veqzo.aimtune.core.metrics.RoundMetrics
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Metrics of a round, each with the direction in which it improves. [scoresRounds] says whether it
 * enters the score the optimizer maximizes. Consistency does not: the spread of the relative
 * first-movement error falls steadily as the sensitivity gets lower (the hand's fixed noise shrinks in
 * degrees), so it has no best sensitivity of its own and would only pull the result toward the slow
 * end (docs/history/REVIEW.md C3). It is kept as a diagnostic.
 */
enum class ScoreMetric(val higherIsBetter: Boolean, val scoresRounds: Boolean = true) {
    ACCURACY(true),
    TIME_TO_HIT(false),
    TRACKING(true),

    /** Share of flashes the player had turned away from when they popped; saturates, so turning faster than needed earns nothing. */
    FLASH_DODGE(true),
    CONSISTENCY(false, scoresRounds = false),
}

/** Relative importance of each metric; only ratios matter. Metrics without a weight count as 0. */
data class MetricWeights(val values: Map<ScoreMetric, Double>) {
    init {
        require(values.values.all { it >= 0.0 && it.isFinite() }) { "weights must be finite and non-negative" }
        require(values.filterKeys { it.scoresRounds }.values.sum() > 0.0) { "at least one weight of a scoring metric must be positive" }
    }

    /** Weights restricted to [available] and scaled to sum to 1. */
    fun normalized(available: Set<ScoreMetric>): Map<ScoreMetric, Double> {
        val picked = available.filter { it.scoresRounds }.associateWith { values[it] ?: 0.0 }
        val total = picked.values.sum()
        require(total > 0.0) { "no available metric has a positive weight" }
        return picked.mapValues { it.value / total }
    }

    companion object {
        /** Defaults of the settings screen: accuracy 40, time to hit 30, tracking 20 (consistency is not scored, see [ScoreMetric]). */
        val DEFAULT = MetricWeights(
            mapOf(ScoreMetric.ACCURACY to 40.0, ScoreMetric.TIME_TO_HIT to 30.0, ScoreMetric.TRACKING to 20.0),
        )
    }
}

/** The metric values of a round that the score uses; a round only has the metrics its scenario produces. */
fun RoundMetrics.scoreInputs(): Map<ScoreMetric, Double> = buildMap {
    if (shots > 0) put(ScoreMetric.ACCURACY, firstShotAccuracy ?: accuracy)
    meanTimeToHitMs?.let { put(ScoreMetric.TIME_TO_HIT, it) }
    errorSpread?.let { put(ScoreMetric.CONSISTENCY, it) }
    trackingOnTargetRatio?.let { put(ScoreMetric.TRACKING, it) }
    flashDodgeRate?.let { put(ScoreMetric.FLASH_DODGE, it) }
}

/**
 * Turns metric values into a 0-100 score relative to the user's own rounds: each metric becomes a
 * z-score against the mean and spread over all given rounds (sign-flipped when lower is better),
 * mapped through the normal CDF, and the results are combined with the weights. See
 * docs/ALGORITHMS.md ("Metrics and score").
 */
class ScoreCalculator(private val weights: MetricWeights, private val minRounds: Int = 3) {
    init {
        require(minRounds >= 2) { "a baseline needs at least 2 rounds" }
    }

    /**
     * One score per round, or null when no metric of that round has a usable baseline. With
     * [scenarios] (the scenario id of each round) every scenario gets its own baselines: a micro
     * round is easier than a flick round, and comparing them would only measure the scenario.
     */
    fun scoreRounds(rounds: List<Map<ScoreMetric, Double>>, scenarios: List<String>? = null): List<Double?> {
        if (scenarios == null) return scoreGroup(rounds)
        require(scenarios.size == rounds.size) { "one scenario per round expected" }
        val scores = arrayOfNulls<Double>(rounds.size)
        for (indices in rounds.indices.groupBy { scenarios[it] }.values) {
            val scored = scoreGroup(indices.map { rounds[it] })
            indices.forEachIndexed { position, index -> scores[index] = scored[position] }
        }
        return scores.toList()
    }

    private fun scoreGroup(rounds: List<Map<ScoreMetric, Double>>): List<Double?> {
        val baselines = ScoreMetric.entries.mapNotNull { metric -> baseline(metric, rounds)?.let { metric to it } }.toMap()
        return rounds.map { round -> scoreRound(round, baselines) }
    }

    private fun baseline(metric: ScoreMetric, rounds: List<Map<ScoreMetric, Double>>): Baseline? {
        val xs = rounds.mapNotNull { it[metric] }
        if (xs.size < minRounds) return null
        val mean = xs.average()
        val sd = sqrt(xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1))
        return if (sd < MIN_SPREAD * maxOf(1.0, abs(mean))) null else Baseline(mean, sd)
    }

    private fun scoreRound(round: Map<ScoreMetric, Double>, baselines: Map<ScoreMetric, Baseline>): Double? {
        val usable = round.keys.filter { it.scoresRounds && it in baselines && (weights.values[it] ?: 0.0) > 0.0 }.toSet()
        if (usable.isEmpty()) return null
        val w = weights.normalized(usable)
        return usable.sumOf { metric ->
            val b = baselines.getValue(metric)
            val sign = if (metric.higherIsBetter) 1.0 else -1.0
            w.getValue(metric) * 100.0 * normalCdf(sign * (round.getValue(metric) - b.mean) / b.sd)
        }
    }

    private data class Baseline(val mean: Double, val sd: Double)

    private companion object {
        /** A spread below this fraction of the mean means the metric did not vary enough to normalize. */
        const val MIN_SPREAD = 1e-9
    }
}

/** Standard normal CDF via the Abramowitz-Stegun 7.1.26 erf approximation (error below 1.5e-7). */
internal fun normalCdf(z: Double): Double {
    val x = abs(z) / sqrt(2.0)
    val t = 1.0 / (1.0 + 0.3275911 * x)
    val poly = ((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592
    val erf = 1.0 - poly * t * exp(-x * x)
    return if (z >= 0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
}
