package dev.veqzo.aimtune.core.accel

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** One first reach of a calibration round: target distance in degrees, peak hand speed in in/s, signed error, and whether it was a speed round. */
data class CalibrationReach(val distance: Double, val speed: Double, val errorRatio: Double, val rushed: Boolean)

/** What the speed-round analysis found; [recommended] is null when the slope is not clearly away from 0 or changes too little. */
data class SpeedRoundFit(
    /** Exponent of the wanted gain: gain ∝ speed^slope (> 0 the player wants more gain when moving faster). */
    val slope: Double,
    val standardError: Double,
    /** Distance bins with enough reaches in both kinds of round. */
    val bins: Int,
    /** Median log speed of rushed minus normal reaches, averaged over the bins: how far the speed rounds moved the hand. */
    val speedContrast: Double,
    val recommended: AccelFit?,
    val reasonCode: AccelReason,
) {
    val z: Double get() = if (standardError > 0.0) abs(slope) / standardError else 0.0
}

/**
 * The speed dependence of the wanted gain from normal and speed rounds (docs/ALGORITHMS.md, "Speed rounds").
 *
 * In normal rounds the speed of a reach is almost fixed by its distance (corr 0.956 in the simulation), so an error that
 * depends on the distance (a range effect: short reaches overshoot, long ones fall short) cannot be told from a gain that
 * depends on the speed. In a speed round the targets vanish quickly and the hand moves the same distances faster. Within
 * one distance bin the two kinds of round then differ in speed but not in distance, and the change of the median error
 * between them over the change of the median log speed is the slope of the wanted log gain against log speed. Every bin
 * has its own level, so any effect of the distance alone drops out. Bins are compared by their medians: the noise of a
 * rushed reach is larger, and a mean of the log error would sink with it (the M3 artefact of docs/history/REVIEW.md). The speed
 * of a single reach is not used as such; it carries the reach's own noise (E1 of the removed docs/OPTIMIZER-REVIEW.md (git history)).
 *
 * What it cannot see: a hand that undershoots more when rushed, whatever the gain, looks like a wish for more gain at
 * speed. The blind test stays the judge.
 */
fun analyseSpeedRounds(
    reaches: List<CalibrationReach>,
    minPerCell: Int = MIN_PER_CELL,
    minZ: Double = MIN_SPEED_ROUND_Z,
    minGainRange: Double = MIN_GAIN_RANGE,
): SpeedRoundFit {
    val used = reaches.filter { it.speed >= AccelAnalysis.MIN_SPEED && (1.0 + it.errorRatio) in AccelAnalysis.MIN_RATIO..AccelAnalysis.MAX_RATIO && it.distance > 0.0 }
    val rushed = used.count { it.rushed }
    val bins = minOf(MAX_DISTANCE_BINS, minOf(rushed, used.size - rushed) / minPerCell)
    if (bins < MIN_DISTANCE_BINS) return SpeedRoundFit(0.0, 0.0, 0, 0.0, null, AccelReason.NOT_ENOUGH_MOVEMENTS)

    val sorted = used.sortedBy { it.distance }
    val contrasts = (0 until bins).mapNotNull { b ->
        val slice = sorted.subList(b * sorted.size / bins, (b + 1) * sorted.size / bins)
        val fast = slice.filter { it.rushed }
        val normal = slice.filter { !it.rushed }
        if (fast.size < minPerCell || normal.size < minPerCell) return@mapNotNull null
        val (mFast, seFast) = medianWithError(fast.map { ln(1.0 + it.errorRatio) })
        val (mNormal, seNormal) = medianWithError(normal.map { ln(1.0 + it.errorRatio) })
        Contrast(median(fast.map { ln(it.speed) }) - median(normal.map { ln(it.speed) }), mFast - mNormal, seFast * seFast + seNormal * seNormal)
    }.filter { it.speed > MIN_SPEED_CONTRAST && it.variance > 0.0 }
    if (contrasts.size < MIN_DISTANCE_BINS) return SpeedRoundFit(0.0, 0.0, contrasts.size, 0.0, null, AccelReason.NARROW_SPEED_RANGE)

    // Weighted least squares through the origin over the bins: error change = gamma * speed change.
    val sxx = contrasts.sumOf { it.speed * it.speed / it.variance }
    val gamma = contrasts.sumOf { it.speed * it.error / it.variance } / sxx
    val chi = contrasts.sumOf { (it.error - gamma * it.speed).let { r -> r * r } / it.variance }
    // More scatter between the bins than their errors allow widens the error of gamma (never narrows it).
    val se = sqrt(1.0 / sxx) * sqrt(max(1.0, chi / (contrasts.size - 1)))
    // A reach that lands at (1 + e) times its distance wanted 1 / (1 + e) times its gain.
    val slope = -gamma
    val contrast = contrasts.map { it.speed }.average()

    val speeds = used.map { it.speed }.sorted()
    val low = speeds.nearestRank((1.0 - BOUND_SHARE) / 2)
    val high = speeds.nearestRank((1.0 + BOUND_SHARE) / 2)
    val gainRange = exp(abs(slope) * ln(high / low))
    val code = when {
        abs(slope) / se < minZ -> AccelReason.NOT_BETTER_THAN_NONE
        gainRange < minGainRange -> AccelReason.SMALL_CHANGE
        else -> AccelReason.FITS
    }
    val fit = if (code == AccelReason.FITS) powerLawTable(slope, low, high, median(used.map { it.speed }), se, gainRange) else null
    return SpeedRoundFit(slope, se, contrasts.size, contrast, fit, code)
}

/**
 * The wanted gain `(v / reference)^slope` as a lookup table over [low]..[high] in/s, flat outside, with factor 1: the gain
 * of a reach at the median speed stays as it is, so the curve does not carry a different sensitivity.
 */
private fun powerLawTable(slope: Double, low: Double, high: Double, reference: Double, se: Double, gainRange: Double): AccelFit {
    val speeds = (0 until LOOKUP_POINTS).map { low * exp(ln(high / low) * it / (LOOKUP_POINTS - 1)) }
    val curve = LookupCurve(speeds, speeds.map { exp(slope * ln(it / reference)) })
    val z = abs(slope) / se
    return AccelFit(SPEED_ROUND_FAMILY, curve, sensitivityFactor = 1.0, chiSquare = 0.0, nullChiSquare = z * z, bicGain = z * z, gainRange = gainRange)
}

/** The value below which the share [q] of the sorted values lie (nearest rank). */
private fun List<Double>.nearestRank(q: Double): Double = this[((size - 1) * q).toInt()]

private class Contrast(val speed: Double, val error: Double, val variance: Double)

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/** The median and its standard error (sqrt(pi/2) times that of the mean, for roughly normal values). */
private fun medianWithError(values: List<Double>): Pair<Double, Double> {
    val mean = values.average()
    val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    return median(values) to sqrt(Math.PI / 2.0) * sd / sqrt(values.size.toDouble())
}

const val SPEED_ROUND_FAMILY = "speed rounds"
const val MIN_PER_CELL = 12
const val MIN_DISTANCE_BINS = 3
const val MAX_DISTANCE_BINS = 8

/** A bin whose speed rounds were less than 5 % faster says nothing about the slope. */
private const val MIN_SPEED_CONTRAST = 0.05

/** Calibrated on flat simulated players (gate section F, docs/ALGORITHMS.md "Speed rounds"); a starting value until then. */
const val MIN_SPEED_ROUND_Z = 3.0
