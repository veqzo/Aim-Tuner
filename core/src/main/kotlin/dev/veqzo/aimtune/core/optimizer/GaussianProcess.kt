package dev.veqzo.aimtune.core.optimizer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One round as the optimizer sees it: where it was played and the round's score. [group] says which
 * kind of round it was (the scenario); scores of one group share an unknown level of their own.
 * [step] is the position of the round within its session (0 for the first): warming up, learning and
 * fatigue show up as a drift of the scores with it.
 */
data class Observation(
    val sessionId: Long,
    val cm360: Double,
    val score: Double,
    val group: Int = 0,
    val step: Int = 0,
    /** When the round was played, in days from any fixed origin; only the time model (`GpHyperParams.driftShare`) uses it. */
    val days: Double = 0.0,
) {
    init {
        require(cm360 > 0.0 && cm360.isFinite() && score.isFinite() && days.isFinite()) { "invalid observation" }
    }

    /** The optimizer works on the log scale. */
    val x: Double get() = ln(cm360)
}

/**
 * Hyperparameters on standardized scores (mean 0, sd 1) and `x = ln(cm360)`.
 * [sessionVariance] is the variance of a per-session offset (a better or worse day),
 * [groupVariance] the same for a per-group (scenario) offset.
 */
data class GpHyperParams(
    val lengthscale: Double,
    val signalVariance: Double,
    val noiseVariance: Double,
    val sessionVariance: Double,
    val groupVariance: Double = 0.0,
    /** Variance of the per-session slope of the score against `step / TREND_STEP_SCALE`; 0 means no drift. */
    val trendVariance: Double = 0.0,
    /**
     * Time model (M14 T7): the score curve is `f0(x) + f1(x, t)`, where f1 has [driftShare] times the variance of f0 and
     * forgets over [driftDays] (exponential, a random walk over days). The posterior is that of the newest day, so older
     * days count less the more the data show the curve moving. 0 means one curve for all days, as before.
     */
    val driftShare: Double = 0.0,
    val driftDays: Double = 21.0,
) {
    init {
        require(driftShare >= 0.0 && driftDays > 0.0) { "driftShare must not be negative and driftDays must be positive" }
    }
}

/** Posterior of the score function on a set of points, in the units of the scores. */
class Posterior(val x: DoubleArray, val mean: DoubleArray, val covariance: DoubleArray) {
    val size: Int get() = x.size
    fun variance(i: Int): Double = covariance[i * size + i].coerceAtLeast(0.0)
    fun sd(i: Int): Double = sqrt(variance(i))
}

/** Matern 5/2 kernel for a distance [r] and lengthscale. */
internal fun matern52(r: Double, lengthscale: Double): Double {
    val z = SQRT5 * abs(r) / lengthscale
    return (1.0 + z + z * z / 3.0) * exp(-z)
}

private val SQRT5 = sqrt(5.0)

/**
 * Gaussian process regression of the round score over `ln(cm360)`:
 * `y = f(x) + offset(session) + slope(session) * step + offset(group) + noise` with a Matern 5/2 prior
 * on f. The offsets and slopes are integrated out through extra "same session", "same group" and
 * "same session, both late" covariance terms, so rounds of several sessions and scenarios combine
 * into one estimate of f and a drift within a session (learning, fatigue) is not mistaken for a
 * better or worse sensitivity.
 * Scores are standardized internally; results come back in score units.
 */
class GaussianProcess private constructor(
    private val xs: DoubleArray,
    private val days: DoubleArray,
    private val yStandardized: DoubleArray,
    private val mean: Double,
    private val scale: Double,
    val hyper: GpHyperParams,
    private val factor: Cholesky,
    private val alpha: DoubleArray,
    val logMarginalLikelihood: Double,
) {
    /** The day the posterior refers to: the newest observation's. */
    private val now: Double = days.maxOrNull() ?: 0.0

    /** Posterior mean and covariance of f at [grid] (ascending x values), on the newest day. */
    fun posterior(grid: DoubleArray): Posterior {
        val n = xs.size
        val m = grid.size
        val cross = Array(m) { j -> DoubleArray(n) { i -> hyper.signalVariance * matern52(xs[i] - grid[j], hyper.lengthscale) * timeFactor(hyper, days[i] - now) } }
        val prior = 1.0 + hyper.driftShare
        val mu = DoubleArray(m) { j -> mean + scale * cross[j].indices.sumOf { cross[j][it] * alpha[it] } }
        val v = Array(m) { j -> factor.solveLower(cross[j]) }
        val cov = DoubleArray(m * m)
        for (a in 0 until m) {
            for (b in 0..a) {
                var dot = 0.0
                for (k in 0 until n) dot += v[a][k] * v[b][k]
                val value = (hyper.signalVariance * matern52(grid[a] - grid[b], hyper.lengthscale) * prior - dot) * scale * scale
                cov[a * m + b] = value
                cov[b * m + a] = value
            }
        }
        return Posterior(grid, mu, cov)
    }

    companion object {
        /** Fits with fixed hyperparameters; null when the covariance cannot be factored. */
        fun fit(observations: List<Observation>, hyper: GpHyperParams): GaussianProcess? {
            require(observations.isNotEmpty()) { "need at least one observation" }
            val n = observations.size
            val ys = observations.map { it.score }
            val mean = ys.average()
            val sd = sqrt(ys.sumOf { (it - mean) * (it - mean) } / maxOf(1, n - 1))
            val scale = if (sd < MIN_SCALE) 1.0 else sd
            val yStd = DoubleArray(n) { (ys[it] - mean) / scale }
            val xs = DoubleArray(n) { observations[it].x }
            val days = DoubleArray(n) { observations[it].days }
            val steps = DoubleArray(n) { observations[it].step / TREND_STEP_SCALE }
            val k = DoubleArray(n * n)
            for (i in 0 until n) {
                for (j in 0 until n) {
                    var v = hyper.signalVariance * matern52(xs[i] - xs[j], hyper.lengthscale) * timeFactor(hyper, days[i] - days[j])
                    if (observations[i].sessionId == observations[j].sessionId) {
                        v += hyper.sessionVariance + hyper.trendVariance * steps[i] * steps[j]
                    }
                    if (observations[i].group == observations[j].group) v += hyper.groupVariance
                    if (i == j) v += hyper.noiseVariance
                    k[i * n + j] = v
                }
            }
            val factor = Cholesky.factor(k, n) ?: return null
            val alpha = factor.solve(yStd)
            val lml = -0.5 * yStd.indices.sumOf { yStd[it] * alpha[it] } - 0.5 * factor.logDeterminant() - 0.5 * n * ln(2.0 * PI)
            return GaussianProcess(xs, days, yStd, mean, scale, hyper, factor, alpha, lml)
        }

        /** `1 + driftShare * exp(-|dt| / driftDays)`: rounds of one day share the moving part fully, distant days hardly. */
        private fun timeFactor(hyper: GpHyperParams, dt: Double): Double =
            if (hyper.driftShare == 0.0) 1.0 else 1.0 + hyper.driftShare * exp(-abs(dt) / hyper.driftDays)

        private const val MIN_SCALE = 1e-9

        /** The trend variance refers to a slope per this many rounds, so a typical session spans about 1-3 units. */
        const val TREND_STEP_SCALE = 10.0
    }
}
