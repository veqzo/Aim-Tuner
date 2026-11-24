package dev.veqzo.aimtune.core.optimizer

import dev.veqzo.aimtune.core.score.normalCdf
import dev.veqzo.aimtune.core.units.SearchRange
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Why a cm/360 was suggested; the app never shows this to the user during a blind test. */
enum class SuggestionKind { DESIGN, EXPLORE, REPEAT }

data class Suggestion(val cm360: Double, val kind: SuggestionKind)

data class CurvePoint(val cm360: Double, val meanScore: Double, val sdScore: Double)

/**
 * What the optimizer knows after a set of rounds. [intervalLowCm360]..[intervalHighCm360] is the
 * central credible interval of the best cm/360. [confidencePercent] is the posterior probability
 * that [optimumCm360] scores within [negligibleDifference] points of the true best.
 */
data class OptimizationResult(
    val optimumCm360: Double,
    /** True when [optimumCm360] is at an end of the searched range, so a better value may lie outside it. */
    val atRangeEdge: Boolean,
    /** True when the scores drift within sessions (the fit chose a trend); the result may then reflect warming up or tiring, and the confidence is capped. */
    val driftDetected: Boolean,
    val intervalLowCm360: Double,
    val intervalHighCm360: Double,
    val confidencePercent: Int,
    /** Score difference below which two sensitivities count as equally good (a tenth of the spread of round scores). */
    val negligibleDifference: Double,
    /** Average score points lost by picking [optimumCm360] instead of the true best, over the posterior. */
    val expectedRegret: Double,
    /** Largest expected improvement of one more round over the current best estimate, in score points. */
    val maxExpectedImprovement: Double,
    val recommendMoreRounds: Boolean,
    /** Rough estimate of the rounds still needed to reach the target confidence; 0 when reached. */
    val roundsToTarget: Int,
    val curve: List<CurvePoint>,
    val hyper: GpHyperParams,
    val rounds: Int,
    val sessions: Int,
    /**
     * The stretch of cm/360 around [optimumCm360] in which the model's mean score is within [negligibleDifference]
     * of the best: sensitivities in it are equivalent as far as this test can tell (docs/history/ASSESSMENT.md A2).
     */
    val equivalentLowCm360: Double = optimumCm360,
    val equivalentHighCm360: Double = optimumCm360,
    /**
     * Time model only: the best sensitivity moved between days (the fit chose a moving curve, or the newest session alone
     * puts its best value outside the interval of all of them). The result then leans on the newest days.
     */
    val dayDriftDetected: Boolean = false,
) {
    /** True when the equivalent stretch is wide, so any value in it is as good as another. */
    val isFlat: Boolean get() = equivalentHighCm360 / equivalentLowCm360 >= FLAT_RATIO

    companion object {
        const val FLAT_RATIO = 1.8
    }
}

data class OptimizerConfig(
    /** Rounds placed by a randomized space-filling design before the model chooses. */
    val initialDesignRounds: Int = 6,
    val gridPoints: Int = 121,
    val posteriorSamples: Int = 400,
    val intervalMass: Double = 0.8,
    /** Confidence (percent) at which more rounds are no longer recommended. */
    val targetConfidencePercent: Int = 85,
    /** Fraction of the score spread that counts as a negligible difference between two sensitivities. */
    val negligibleFraction: Double = 0.2,
    /** Below this many rounds the confidence is capped at 50 %. */
    val minRoundsForFullConfidence: Int = 10,
    val minNegligibleDifference: Double = 0.5,
    val repeatEvery: Int = 6,
    /** After the initial design, every this many rounds is again a space-filling point instead of the model's choice. */
    val interleaveEvery: Int = 4,
    /** Hyperparameters are searched on at most this many of the newest rounds; the fit itself uses all of them. */
    val maxRoundsForHyperSearch: Int = 150,
    /** Other cm/360 values within this log distance of the previous round are not suggested next. */
    val exclusionLn: Double = 0.03,
    val maxRounds: Int = 80,
    /**
     * Time model of M14 (sensitivity v2): the fit may let the score curve move over days (`GpHyperParams.driftShare`) and
     * rounds older than [maxAgeDays] before the newest one are left out. False keeps the legacy model.
     */
    val timeDrift: Boolean = false,
    val maxAgeDays: Double = 42.0,
) {
    init {
        require(initialDesignRounds >= 2 && gridPoints >= 20 && posteriorSamples >= 50) { "config too small" }
        require(intervalMass in 0.5..0.99 && targetConfidencePercent in 1..99) { "invalid interval or target settings" }
    }
}

/**
 * Bayesian optimization of the sensitivity: a Gaussian process over `ln(cm360)` of the round score,
 * expected improvement to choose the next cm/360, occasional repeats to learn the noise, and a
 * credible interval of the best cm/360 for the result and the confidence. It is a pure function of
 * the history (and the seed), so a test can be stopped and continued, even across sessions.
 * See docs/ALGORITHMS.md ("Bayesian optimization").
 */
class SensitivityOptimizer(
    private val range: SearchRange,
    private val seed: Long = 0L,
    private val config: OptimizerConfig = OptimizerConfig(),
) {
    private val lnLo = ln(range.minCm360)
    private val lnHi = ln(range.maxCm360)
    private val width = lnHi - lnLo
    private val grid = DoubleArray(config.gridPoints) { lnLo + width * it / (config.gridPoints - 1) }

    fun suggestNext(all: List<Observation>): Suggestion {
        val history = recent(all)
        val n = history.size
        if (n < config.initialDesignRounds) return Suggestion(designPoint(n), SuggestionKind.DESIGN)
        // Every few rounds a fresh design point: sensitivities spread over the whole run keep a drift in time
        // from being confused with a difference between sensitivities.
        if (n % config.interleaveEvery == config.interleaveEvery - 1) return Suggestion(designPoint(n), SuggestionKind.DESIGN)
        val gp = fitBest(history) ?: return Suggestion(designPoint(n), SuggestionKind.DESIGN)
        val posterior = gp.posterior(grid)
        val last = history.last().x
        if (shouldRepeat(history)) {
            val best = history.filter { abs(it.x - last) > config.exclusionLn }
                .maxByOrNull { posteriorMeanAt(posterior, it.x) }
            if (best != null) return Suggestion(best.cm360, SuggestionKind.REPEAT)
        }
        val ei = expectedImprovement(posterior)
        val pick = grid.indices.filter { abs(grid[it] - last) > config.exclusionLn }.maxByOrNull { ei[it] }
            ?: grid.indices.maxBy { ei[it] }
        return Suggestion(exp(grid[pick]), SuggestionKind.EXPLORE)
    }

    /** Null while there are too few rounds for a model (fewer than 3). */
    fun analyze(all: List<Observation>): OptimizationResult? {
        val history = recent(all)
        if (history.size < MIN_ROUNDS_FOR_RESULT) return null
        val gp = fitBest(history) ?: return null
        val posterior = gp.posterior(grid)
        val best = grid.indices.maxBy { posterior.mean[it] }
        val scores = history.map { it.score }
        val spread = sqrt(scores.sumOf { (it - scores.average()) * (it - scores.average()) } / (scores.size - 1))
        val negligible = maxOf(config.minNegligibleDifference, config.negligibleFraction * spread)
        val summary = sampleOptimum(posterior, best, negligible, Random(seed xor (history.size.toLong() * SEED_MIX)))
        val maxEi = expectedImprovement(posterior).max()
        // With few rounds the noise is unknown, so a high confidence would be an artifact of the fit.
        val optimumX = refinedOptimumX(posterior, best)
        // A best value at the edge of the searched range may only be the end of a slope: the real optimum can lie outside.
        val atEdge = optimumX - lnLo < EDGE_FRACTION * width || lnHi - optimumX < EDGE_FRACTION * width
        val rawConfidence = (100.0 * summary.probabilityNegligibleLoss).toInt()
        val drift = gp.hyper.trendVariance > 0.0
        val dayDrift = gp.hyper.driftShare > 0.0 || newestSessionOutside(history, summary)
        val limited = history.size < config.minRoundsForFullConfidence || atEdge
        val capped = if (limited) minOf(rawConfidence, LIMITED_CONFIDENCE_CAP) else rawConfidence
        val confidence = if (drift) minOf(capped, DRIFT_CONFIDENCE_CAP) else capped
        val target = config.targetConfidencePercent
        val more = confidence < target && history.size < config.maxRounds
        return OptimizationResult(
            optimumCm360 = exp(optimumX),
            atRangeEdge = atEdge,
            driftDetected = drift,
            dayDriftDetected = dayDrift,
            intervalLowCm360 = exp(summary.lowX),
            intervalHighCm360 = exp(summary.highX),
            confidencePercent = confidence,
            negligibleDifference = negligible,
            expectedRegret = summary.regret,
            maxExpectedImprovement = maxEi,
            recommendMoreRounds = more,
            roundsToTarget = if (confidence >= target) 0 else roundsNeeded(history.size, confidence, target),
            curve = grid.indices.map { CurvePoint(exp(grid[it]), posterior.mean[it], posterior.sd(it)) },
            hyper = gp.hyper,
            rounds = history.size,
            sessions = history.map { it.sessionId }.distinct().size,
            equivalentLowCm360 = exp(grid[equivalentEdge(posterior, best, negligible, -1)]),
            equivalentHighCm360 = exp(grid[equivalentEdge(posterior, best, negligible, +1)]),
        )
    }

    /** With the time model, the rounds of the last [OptimizerConfig.maxAgeDays] before the newest one; all rounds otherwise. */
    private fun recent(history: List<Observation>): List<Observation> {
        if (!config.timeDrift || history.isEmpty()) return history
        val newest = history.maxOf { it.days }
        return history.filter { it.days >= newest - config.maxAgeDays }
    }

    /**
     * Time model only: whether the newest session, fitted alone, puts its best value outside the 80 % interval of all
     * rounds. It needs enough rounds of its own; a short session says too little to raise the alarm.
     */
    private fun newestSessionOutside(history: List<Observation>, all: OptimumSummary): Boolean {
        if (!config.timeDrift) return false
        val newest = history.maxBy { it.days }.sessionId
        val own = history.filter { it.sessionId == newest }
        if (own.size < MIN_ROUNDS_FOR_SESSION_CHECK || own.size == history.size) return false
        val gp = fitBest(own, timeSearch = false) ?: return false
        val posterior = gp.posterior(grid)
        val best = refinedOptimumX(posterior, grid.indices.maxBy { posterior.mean[it] })
        return best < all.lowX || best > all.highX
    }

    /** Grid index of the end of the stretch around [bestIndex] (in direction [step]) that scores within [negligible] of the best. */
    private fun equivalentEdge(posterior: Posterior, bestIndex: Int, negligible: Double, step: Int): Int {
        var i = bestIndex
        while (i + step in grid.indices && posterior.mean[i + step] >= posterior.mean[bestIndex] - negligible) i += step
        return i
    }

    /** The i-th point of a seeded, shuffled, stratified design over the log range. */
    private fun designPoint(index: Int): Double {
        // Every pass over the design uses its own shuffle and jitter; pass 0 is the initial design.
        val random = Random(seed xor ((index / config.initialDesignRounds).toLong() * SEED_MIX))
        val n = config.initialDesignRounds
        val strata = (0 until n).shuffled(random)
        val jitter = List(n) { DESIGN_JITTER_MIN + random.nextDouble() * (1.0 - 2 * DESIGN_JITTER_MIN) }
        val slot = strata[index % n]
        return exp(lnLo + width * (slot + jitter[slot]) / n)
    }

    private fun shouldRepeat(history: List<Observation>): Boolean {
        val n = history.size
        if (n < MIN_ROUNDS_FOR_REPEAT) return false
        val hasRepeat = history.indices.any { i ->
            (i + 1 until n).any { j -> history[i].sessionId == history[j].sessionId && abs(history[i].x - history[j].x) < REPEAT_TOLERANCE_LN }
        }
        return !hasRepeat || n % config.repeatEvery == 0
    }

    private fun posteriorMeanAt(posterior: Posterior, x: Double): Double {
        val i = ((x - lnLo) / width * (config.gridPoints - 1)).toInt().coerceIn(0, config.gridPoints - 1)
        return posterior.mean[i]
    }

    private fun expectedImprovement(posterior: Posterior): DoubleArray {
        val incumbent = posterior.mean.max()
        return DoubleArray(posterior.size) { i ->
            val sd = posterior.sd(i)
            if (sd < MIN_SD) {
                0.0
            } else {
                val z = (posterior.mean[i] - incumbent) / sd
                (posterior.mean[i] - incumbent) * normalCdf(z) + sd * normalPdf(z)
            }
        }
    }

    /** The chance of a negligible loss is assumed to fail proportionally to 1/rounds; a rough guide, not a guarantee. */
    private fun roundsNeeded(rounds: Int, confidence: Int, target: Int): Int {
        val failureNow = (100 - confidence).coerceAtLeast(1).toDouble()
        val needed = rounds * failureNow / (100 - target)
        return (needed - rounds).toInt().coerceIn(1, MAX_ROUNDS_ESTIMATE)
    }

    private data class OptimumSummary(val lowX: Double, val highX: Double, val regret: Double, val probabilityNegligibleLoss: Double)

    /** Draws functions from the posterior and summarizes where their maxima lie. */
    private fun sampleOptimum(posterior: Posterior, bestIndex: Int, negligible: Double, random: Random): OptimumSummary {
        val m = posterior.size
        val covariance = posterior.covariance.copyOf()
        val meanVariance = (0 until m).sumOf { covariance[it * m + it] } / m
        for (i in 0 until m) covariance[i * m + i] += SAMPLING_JITTER * maxOf(meanVariance, MIN_SD)
        val factor = Cholesky.factor(covariance, m)
        if (factor == null) {
            val half = Z_80 * posterior.sd(bestIndex) / maxOf(abs(posterior.mean[bestIndex]), 1.0) * width
            return OptimumSummary(posterior.x[bestIndex] - half, posterior.x[bestIndex] + half, 0.0, 0.0)
        }
        val argmaxX = DoubleArray(config.posteriorSamples)
        var regret = 0.0
        var negligibleLoss = 0
        repeat(config.posteriorSamples) { s ->
            val draw = factor.multiplyLower(DoubleArray(m) { random.gaussian() })
            var top = 0
            for (i in 0 until m) if (draw[i] + posterior.mean[i] > draw[top] + posterior.mean[top]) top = i
            argmaxX[s] = posterior.x[top]
            val loss = (draw[top] + posterior.mean[top]) - (draw[bestIndex] + posterior.mean[bestIndex])
            regret += loss
            if (loss <= negligible) negligibleLoss++
        }
        argmaxX.sort()
        val tail = (1.0 - config.intervalMass) / 2.0
        val low = argmaxX[(tail * (argmaxX.size - 1)).toInt()]
        val high = argmaxX[((1.0 - tail) * (argmaxX.size - 1)).toInt()]
        return OptimumSummary(low, high, regret / config.posteriorSamples, negligibleLoss.toDouble() / config.posteriorSamples)
    }

    /** Parabola through the best grid point and its neighbours, for a position finer than the grid. */
    private fun refinedOptimumX(posterior: Posterior, best: Int): Double {
        if (best == 0 || best == posterior.size - 1) return posterior.x[best]
        val y0 = posterior.mean[best - 1]
        val y1 = posterior.mean[best]
        val y2 = posterior.mean[best + 1]
        val denominator = y0 - 2 * y1 + y2
        if (denominator >= 0.0) return posterior.x[best]
        val offset = 0.5 * (y0 - y2) / denominator
        return posterior.x[best] + offset.coerceIn(-1.0, 1.0) * (posterior.x[best + 1] - posterior.x[best])
    }

    /** Best hyperparameters from a small grid by marginal likelihood, scaled to the search range. */
    private fun fitBest(history: List<Observation>, timeSearch: Boolean = config.timeDrift): GaussianProcess? {
        val multiSession = history.map { it.sessionId }.distinct().size > 1
        val multiGroup = history.map { it.group }.distinct().size > 1
        // A long history makes every fit slow (cubic); the hyperparameters hardly change after a few hundred rounds.
        val searchOn = if (history.size > config.maxRoundsForHyperSearch) history.takeLast(config.maxRoundsForHyperSearch) else history
        var bestHyper: GpHyperParams? = null
        var bestLml = Double.NEGATIVE_INFINITY
        for (ls in LENGTHSCALE_FACTORS) for (sv in SIGNAL_VARIANCES) for (nv in NOISE_VARIANCES) {
            for (tv in if (multiSession) SESSION_VARIANCES else listOf(0.0)) for (gv in if (multiGroup) GROUP_VARIANCES else listOf(0.0)) {
                val hyper = GpHyperParams(ls * width, sv, nv, tv, gv)
                val gp = GaussianProcess.fit(searchOn, hyper) ?: continue
                if (gp.logMarginalLikelihood > bestLml) {
                    bestLml = gp.logMarginalLikelihood
                    bestHyper = hyper
                }
            }
        }
        // The drift is searched only around the best fit without one (a third of the cost of a joint search).
        bestHyper?.let { base ->
            for (trend in TREND_VARIANCES.filter { it > 0.0 }) {
                val hyper = base.copy(trendVariance = trend)
                val gp = GaussianProcess.fit(searchOn, hyper) ?: continue
                if (gp.logMarginalLikelihood > bestLml + TREND_MIN_LML_GAIN) {
                    bestLml = gp.logMarginalLikelihood
                    bestHyper = hyper
                }
            }
        }
        // The time model is searched around the best fit as well, and only when the rounds span several days.
        val spansDays = history.maxOf { it.days } - history.minOf { it.days } >= MIN_DAYS_FOR_TIME_MODEL
        if (timeSearch && multiSession && spansDays) {
            bestHyper?.let { base ->
                for (share in DRIFT_SHARES) for (days in DRIFT_DAYS) {
                    val hyper = base.copy(driftShare = share, driftDays = days)
                    val gp = GaussianProcess.fit(searchOn, hyper) ?: continue
                    if (gp.logMarginalLikelihood > bestLml + TIME_MIN_LML_GAIN) {
                        bestLml = gp.logMarginalLikelihood
                        bestHyper = hyper
                    }
                }
            }
        }
        return bestHyper?.let { GaussianProcess.fit(history, it) }
    }

    private fun normalPdf(z: Double) = exp(-0.5 * z * z) / sqrt(2.0 * PI)

    private fun Random.gaussian(): Double = sqrt(-2.0 * ln(1.0 - nextDouble())) * cos(2.0 * PI * nextDouble())

    private companion object {
        const val MIN_ROUNDS_FOR_RESULT = 3
        const val MIN_ROUNDS_FOR_REPEAT = 8
        const val REPEAT_TOLERANCE_LN = 0.02
        const val DESIGN_JITTER_MIN = 0.15
        const val MIN_SD = 1e-9
        const val SAMPLING_JITTER = 1e-6
        const val Z_80 = 1.2816
        const val LIMITED_CONFIDENCE_CAP = 50

        /** With a drift in the scores a time trend and the sensitivity can not be fully separated. */
        const val DRIFT_CONFIDENCE_CAP = 60

        /** An optimum within this share of the log range width from either end counts as at the edge. */
        const val EDGE_FRACTION = 0.02
        const val MAX_ROUNDS_ESTIMATE = 40
        val SEED_MIX = 0x9E3779B97F4A7C15uL.toLong()
        val LENGTHSCALE_FACTORS = listOf(0.12, 0.2, 0.3, 0.45, 0.7)
        val SIGNAL_VARIANCES = listOf(0.5, 1.0, 2.0)
        val NOISE_VARIANCES = listOf(0.15, 0.3, 0.5, 0.8, 1.2)
        val SESSION_VARIANCES = listOf(0.0, 0.1, 0.4)
        val GROUP_VARIANCES = listOf(0.0, 0.4)
        val TREND_VARIANCES = listOf(0.0, 0.05, 0.3)

        /** A trend must raise the log marginal likelihood by this much to be taken (a mild Occam penalty against false alarms). */
        const val TREND_MIN_LML_GAIN = 1.0

        /** Share of the curve's variance that may move over days, and how many days it takes to forget; chosen like the trend. */
        val DRIFT_SHARES = listOf(0.5, 1.0, 2.0, 4.0)
        val DRIFT_DAYS = listOf(2.0, 7.0, 21.0)
        const val TIME_MIN_LML_GAIN = 1.0
        const val MIN_DAYS_FOR_TIME_MODEL = 0.5

        /** The newest session alone must have this many rounds to raise the drift hint; with 10 it did so in 30 % of runs without drift. */
        const val MIN_ROUNDS_FOR_SESSION_CHECK = 20
    }
}
