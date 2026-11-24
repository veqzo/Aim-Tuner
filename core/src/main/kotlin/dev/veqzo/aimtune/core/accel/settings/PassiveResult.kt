package dev.veqzo.aimtune.core.accel.settings

import kotlin.random.Random

/**
 * The answer of a passive setting analysis (docs/ALGORITHMS.md, "Passive setting analyses"). [value] and [estimate] are in the
 * unit of the setting (degrees for rotation and snap, a ratio for Y/X, L/R, U/D); [low] and [high] are the 95 % interval and [n]
 * the number of reaches the estimate rests on.
 */
sealed interface PassiveResult {
    /** The data say the setting should change to [value] (already rounded to what Raw Accel takes). */
    data class Recommend(val value: Double, val low: Double, val high: Double, val n: Int) : PassiveResult

    /** The data do not show a deviation worth correcting; [estimate] is what was measured. */
    data class NoChange(val estimate: Double, val low: Double, val high: Double, val n: Int) : PassiveResult

    /** Fewer than [needed] usable reaches ([n]); nothing is claimed. */
    data class NotEnoughData(val n: Int, val needed: Int) : PassiveResult
}

/** Interval level of every analysis: the 2.5 % and 97.5 % points of a bootstrap. */
internal const val INTERVAL_LOW = 0.025
internal const val INTERVAL_HIGH = 0.975
internal const val BOOTSTRAP_RESAMPLES = 400
private const val BOOTSTRAP_SEED = 20_250_611L

internal fun List<Double>.percentile(p: Double): Double {
    require(isNotEmpty()) { "no values" }
    val sorted = sorted()
    val position = p * (sorted.size - 1)
    val lower = position.toInt()
    val upper = minOf(lower + 1, sorted.size - 1)
    return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
}

internal fun List<Double>.median(): Double = percentile(0.5)

/** The 95 % bootstrap interval of [statistic] over [sample]; the random stream is fixed, so an analysis is deterministic. */
internal fun <T> bootstrapInterval(sample: List<T>, statistic: (List<T>) -> Double): Pair<Double, Double> {
    val random = Random(BOOTSTRAP_SEED + sample.size)
    val values = List(BOOTSTRAP_RESAMPLES) { statistic(List(sample.size) { sample[random.nextInt(sample.size)] }) }
    return values.percentile(INTERVAL_LOW) to values.percentile(INTERVAL_HIGH)
}
