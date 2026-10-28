package de.yuuto.aimtune.core.accel

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Steps defined by what the player can feel (docs/FIND-CURVE-REDESIGN.md section 12). Scaling the rise of the player's own curve
 * ([OwnCurveStrength]) moves the gain by only a few percent at the speeds of the hand, so a step is a gain of `+X` at the fast end
 * of the hand's own speeds, [anchorSlow] to [anchorFast] (the median and 95th percentile of the first-reach speeds), laid on top of the
 * curve: `C(v) = B(v) * (1 + X)^h(u(v))` with a smoothstep `h` of the log position `u` between the anchors. Below the slow anchor the
 * curve is untouched, so micro-adjustments and the base sensitivity stay as they are; above the fast anchor the extra stays at `1 + X`.
 *
 * [maxStep] is the largest `X` whose change of the gain stays within [StrengthDraft.BANDWIDTH_Z] error spreads in every bin of the
 * common speeds; [steps] are the rungs of [FeltStep.LADDER] up to it.
 */
data class FeltPlan(val anchorSlow: Double, val anchorFast: Double, val maxStep: Double, val steps: List<Double>)

object FeltStep {
    /** Gain steps at the fast end of the hand's speeds; about 8 % is taken as the smallest change a player notices (an assumption, not measured). */
    val LADDER = listOf(0.08, 0.15, 0.25)

    /** The anchors must be at least this far apart, else "fast" cannot be told from "slow". */
    const val MIN_SPAN = 1.5

    /** Bins where the step has hardly started do not limit it. */
    private const val MIN_WEIGHT = 0.05

    /** The smoothstep position of [speed] between the anchors: 0 at or below the slow one, 1 at or above the fast one. */
    fun weight(speed: Double, slow: Double, fast: Double): Double {
        val u = (ln(speed / slow) / ln(fast / slow)).coerceIn(0.0, 1.0)
        return u * u * (3.0 - 2.0 * u)
    }

    fun multiplier(speed: Double, step: Double, slow: Double, fast: Double): Double = exp(ln(1.0 + step) * weight(speed, slow, fast))
}

internal fun feltPlan(analysis: AccelAnalysis, z: Double): FeltPlan? {
    val slow = analysis.speedQuantile(0.5)
    val fast = analysis.speedQuantile(0.95)
    if (fast / slow < FeltStep.MIN_SPAN) return null
    val low = analysis.speedQuantile(0.05)
    val room = analysis.points.filter { it.speed in low..fast }.mapNotNull { point ->
        val w = FeltStep.weight(point.speed, slow, fast)
        if (w < 0.05) null else z * binSpread(point) / w
    }.minOrNull() ?: Double.POSITIVE_INFINITY
    val maxStep = exp(room) - 1.0
    return FeltPlan(slow, fast, maxStep, FeltStep.LADDER.filter { it <= maxStep })
}

/** The felt steps on top of the player's [base] as tables on the grid of [OwnCurveStrength]; [dpi] is the DPI of the settings. */
class FeltStepFamily(base: RawAccelProfile, dpi: Double) {
    private val grid = OwnCurveStrength(base, dpi)

    fun table(step: Double, slow: Double, fast: Double): LookupCurve =
        LookupCurve(grid.speeds, grid.speeds.indices.map { grid.gains[it] * FeltStep.multiplier(grid.speeds[it], step, slow, fast) })

    /** The step as a candidate for the blind test; the table carries the level, so the sensitivity factor stays 1. */
    fun offer(step: Double, slow: Double, fast: Double): AccelFit {
        require(step > 0.0) { "a felt step is a rise of the gain" }
        return AccelFit(
            family = "your curve, +%.0f %% at fast speeds".format(java.util.Locale.ROOT, step * 100.0),
            curve = table(step, slow, fast),
            sensitivityFactor = 1.0,
            chiSquare = 0.0,
            nullChiSquare = 0.0,
            bicGain = 0.0,
            gainRange = 1.0 + step,
        )
    }

    /** The step a played table stands for (its plateau over the base), or null when it is no felt step of this base. */
    fun stepOf(curve: AccelCurve): Double? {
        if (curve !is LookupCurve || curve.speeds.size != grid.speeds.size) return null
        val ratio = curve.multipliers.indices.map { curve.multipliers[it] / grid.gains[it] }
        if (ratio.first() < 1.0 - TOLERANCE || ratio.first() > 1.0 + TOLERANCE) return null
        if (ratio.zipWithNext().any { (a, b) -> b < a - TOLERANCE }) return null
        val plateau = ratio.last() - 1.0
        return plateau.takeIf { it > TOLERANCE && abs(ratio[ratio.size - 2] - ratio.last()) < TOLERANCE }
    }

    private companion object {
        const val TOLERANCE = 1e-4
    }
}
