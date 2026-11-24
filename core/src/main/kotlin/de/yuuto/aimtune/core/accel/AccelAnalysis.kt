package de.yuuto.aimtune.core.accel

import de.yuuto.aimtune.core.metrics.RoundMetrics
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** One first movement: its peak hand speed in inches per second and its signed endpoint error (> 0 overshoot). */
data class MovementSample(val speed: Double, val errorRatio: Double)

/**
 * The samples of rounds played without acceleration, taken from their analysed movements. A reach toward a target that moved
 * is left out: the hand aimed at where the target would be, so its endpoint error says nothing about the gain the hand wanted
 * (docs/ALGORITHMS.md "Moving targets"). Only reaches toward targets that stood still enter.
 */
fun movementSamples(metrics: List<RoundMetrics>, dpi: Double): List<MovementSample> =
    metrics.flatMap { it.movements }.filterNot { it.targetMoved }.mapNotNull { movement ->
        val peak = movement.firstPeakSpeedCountsPerMs ?: return@mapNotNull null
        val error = movement.firstErrorRatio ?: return@mapNotNull null
        MovementSample(AccelProcessor.speedInPerSecond(peak, dpi), error)
    }

/** The gain the player wanted around one speed, relative to the average over all movements. */
data class GainPoint(val speed: Double, val logGain: Double, val standardError: Double, val count: Int) {
    val gain: Double get() = exp(logGain)
}

/**
 * Error against speed: a movement that landed at `(1 + e)` times its distance wanted `1 / (1 + e)`
 * times the gain it got. Movements are grouped into bins of similar speed (equal counts), each bin's
 * median log ratio gives the wanted log gain there, and the whole is normalized so the median wanted
 * log gain over all movements is 0. The median, not the mean: the noise of a reach is about symmetric
 * in the error itself, so the mean of its logarithm is pulled down by an amount that grows with the
 * relative noise, which differs between slow, small reaches and fast, large ones; the mean therefore
 * invented a speed dependence for players who have none (docs/history/REVIEW.md M3): it says how the wanted gain changes with speed, not what the
 * sensitivity should be (that is the optimizer's job). See docs/ALGORITHMS.md ("Acceleration").
 */
class AccelAnalysis(
    samples: List<MovementSample>,
    private val minPerBin: Int = MIN_PER_BIN,
    minSpeed: Double = MIN_SPEED,
    /** Upper limit of the number of speed bins; more, smaller bins approach a fit on every single movement. */
    private val maxBins: Int = MAX_BINS,
    /**
     * The gain the rounds were played with at a hand speed (in/s), when they were played with a curve of the player's own and not
     * plain: a reach lands at `delivered / wanted` times its distance, so the delivered gain is divided out and the points say what
     * the player wants. Only the shape counts (a constant factor changes nothing, the points are centred); null is plain.
     */
    private val delivered: ((Double) -> Double)? = null,
) {
    /**
     * Samples used: movements with a plausible ratio (the first movement went toward the target) and a hand
     * speed of at least [minSpeed]. Slower reaches are small ones, and their error depends on their size even
     * with a constant gain; on simulated players without any preference it looked like a falling gain of
     * the size of a 15 % preference (docs/history/ASSESSMENT.md B1), and Raw Accel curves start at gain 1 there anyway.
     */
    val used: List<MovementSample> = samples.filter { it.speed >= minSpeed && (1.0 + it.errorRatio) in MIN_RATIO..MAX_RATIO }

    val points: List<GainPoint>

    /** The speed below which the share [q] of the used movements lie (nearest rank); 0 without movements. */
    fun speedQuantile(q: Double): Double {
        require(q in 0.0..1.0) { "q must be in 0..1" }
        if (used.isEmpty()) return 0.0
        val sorted = used.map { it.speed }.sorted()
        return sorted[((sorted.size - 1) * q).toInt()]
    }

    /** Ratio of the fastest to the slowest bin speed; a curve cannot be judged without a wide range. */
    val speedRange: Double get() = if (points.size < 2) 1.0 else points.last().speed / points.first().speed

    init {
        // ln(1 + e) is minus the log of the wanted gain when plain; with a delivered curve the delivered log gain is taken off first.
        val logRatios = used.map { ln(1.0 + it.errorRatio) - (delivered?.let { d -> ln(d(it.speed)) } ?: 0.0) }
        val centerLog = if (logRatios.isEmpty()) 0.0 else median(logRatios)
        val bins = binCount(used.size)
        val sorted = used.indices.sortedBy { used[it].speed }
        points = if (bins < 2) {
            emptyList()
        } else {
            (0 until bins).map { b ->
                val slice = sorted.subList(b * sorted.size / bins, (b + 1) * sorted.size / bins)
                val ys = slice.map { logRatios[it] }
                val mean = ys.average()
                val sd = if (ys.size > 1) sqrt(ys.sumOf { (it - mean) * (it - mean) } / (ys.size - 1)) else 0.0
                val speeds = slice.map { used[it].speed }.sorted()
                // The standard error of a median of roughly normal values is sqrt(pi / 2) times that of the mean.
                GainPoint(speeds[speeds.size / 2], centerLog - median(ys), MEDIAN_SE_FACTOR * sd / sqrt(ys.size.toDouble()), ys.size)
            }
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun binCount(count: Int): Int = (count / minPerBin).coerceAtMost(maxBins).let { if (it < MIN_BINS) 0 else it }

    companion object {
        private val MEDIAN_SE_FACTOR = sqrt(Math.PI / 2.0)
        const val MIN_PER_BIN = 20

        /** Hand speed in inches per second below which a reach is not used to judge a gain preference. */
        const val MIN_SPEED = 3.0
        const val MIN_BINS = 4
        const val MAX_BINS = 10

        /** A first movement that landed at under a fifth or over three times the distance was not a reach for this target. */
        const val MIN_RATIO = 0.2
        const val MAX_RATIO = 3.0
    }
}
