package de.yuuto.aimtune.core.scenario

import kotlin.random.Random

/** One point of a [QuantileCurve]: [share] of the values are at most [value]. */
data class QuantilePoint(val share: Double, val value: Double)

/**
 * A distribution given by its quantiles, sampled by the inverse CDF with straight lines between the points. It keeps the
 * shape of a measured distribution (M23: the engagement distances of open-aim's flash drills) without inventing a family.
 * The first point must be at share 0 and the last at share 1, so the curve has a finite minimum and maximum.
 */
data class QuantileCurve(val points: List<QuantilePoint>) {
    init {
        require(points.size >= 2) { "a quantile curve needs at least two points" }
        require(points.first().share == 0.0 && points.last().share == 1.0) { "the quantiles must start at share 0 and end at share 1" }
        require(points.all { it.value.isFinite() && it.value > 0.0 }) { "quantile values must be positive" }
        require(points.zipWithNext().all { (a, b) -> b.share > a.share && b.value >= a.value }) { "quantiles must increase" }
    }

    val min: Double get() = points.first().value
    val max: Double get() = points.last().value
    val range: PositiveRange get() = PositiveRange(min, max)

    /** The value below which [share] of the draws fall. */
    fun valueAt(share: Double): Double {
        require(share in 0.0..1.0) { "share must be in 0..1" }
        val upper = points.indexOfFirst { it.share >= share }
        if (upper <= 0) return points.first().value
        val a = points[upper - 1]
        val b = points[upper]
        return a.value + (b.value - a.value) * (share - a.share) / (b.share - a.share)
    }

    /** The share of draws at or below [value]; the inverse of [valueAt]. */
    fun shareBelow(value: Double): Double {
        if (value <= min) return 0.0
        if (value >= max) return 1.0
        val upper = points.indexOfFirst { it.value >= value }
        val a = points[upper - 1]
        val b = points[upper]
        return if (b.value == a.value) b.share else a.share + (b.share - a.share) * (value - a.value) / (b.value - a.value)
    }

    fun draw(random: Random): Double = valueAt(random.nextDouble())
}
