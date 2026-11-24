package dev.veqzo.aimtune.core.accel.settings

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Standard deviation and the robust one (1.4826 x median absolute deviation) of a sample. */
data class Spread(val n: Int, val sd: Double, val robustSd: Double)

/**
 * How noisy first movements are, from the same reaches and ratios the analyses use: the spread of the angle between the first
 * movement and the target direction (degrees) and of `ln(got / need)` along each axis (ratio clipped as in [AxisRatioAnalysis],
 * targets at least 4 degrees away along the axis, both directions of the axis pooled). Used to compare a simulated hand with
 * stored rounds.
 */
data class ReachSpread(val reaches: Int, val angle: Spread?, val horizontalLogRatio: Spread?, val verticalLogRatio: Spread?)

fun reachSpread(reaches: List<FirstReach>): ReachSpread {
    val angles = reaches.filter { it.gotX != 0.0 || it.gotY != 0.0 }.map(RotationAnalysis::angleDegrees)
    val horizontal = AxisRatioAnalysis.samples(reaches, true, true) + AxisRatioAnalysis.samples(reaches, true, false)
    val vertical = AxisRatioAnalysis.samples(reaches, false, true) + AxisRatioAnalysis.samples(reaches, false, false)
    return ReachSpread(reaches.size, spreadOf(angles), spreadOf(horizontal.map { ln(it.ratio) }), spreadOf(vertical.map { ln(it.ratio) }))
}

private fun spreadOf(values: List<Double>): Spread? {
    if (values.size < 2) return null
    val mean = values.average()
    val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    val median = values.median()
    return Spread(values.size, sd, 1.4826 * values.map { abs(it - median) }.median())
}
