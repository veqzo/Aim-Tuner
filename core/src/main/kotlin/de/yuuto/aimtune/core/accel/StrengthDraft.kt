package de.yuuto.aimtune.core.accel

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/** How the direction of a [StrengthDraft] came about. */
enum class DraftBasis {
    /** The hand's own errors point to a clearly stronger rise than the curve has (the range of the estimate lies above 1). */
    MEASURED_STRONGER,

    /** The errors point to a clearly weaker one (the range lies below [CurveStrength.FALLING_MAX_HIGH], the rule falling curves have). */
    MEASURED_WEAKER,

    /** Nothing is clear: a small step toward a stronger rise, the aim of high gain for fast turns and low gain for small corrections. */
    DESIGN,
}

/**
 * A strength for the player's own curve drawn from the movement profile, not found by a search (docs/FIND-CURVE-REDESIGN.md
 * step 5; the search could not find the direction, step 2). The strength scales the rise of the curve ([OwnCurveStrength]).
 * [strength] is where the draft ends and [stage1] the first, half-way step.
 */
data class StrengthDraft(
    /** The rise the errors ask for, as a strength (1 = the curve as it is), and its standard error; infinite when the curve does not act at the hand's speeds. */
    val estimate: Double,
    val standardError: Double,
    /** The largest and smallest strength whose change of the gain stays inside the measured error bandwidth at the hand's speeds. */
    val ceiling: Double,
    val floor: Double,
    val strength: Double,
    val stage1: Double,
    val basis: DraftBasis,
    /** Felt steps on top of the curve at the hand's own speeds; null when the speeds do not span enough to tell fast from slow. */
    val felt: FeltPlan? = null,
) {
    companion object {
        /** How many error standard deviations the gain may change by at any speed in the common range. */
        const val BANDWIDTH_Z = 0.5

        /** Without evidence the draft goes this share of the way to the ceiling. */
        const val DESIGN_SHARE = 0.25

        /**
         * The change of the log gain across the common speeds that the errors must imply before a direction counts as measured. The first
         * reaches of a hand miss a little more the faster they are whatever gain it wants (5 % in the gate harness); on a curve that
         * rises little over the hand's speeds that trend is multiplied into a large "strength", so a smaller implied change is taken as
         * the trend and the direction stays the design default.
         */
        const val TREND_ALLOWANCE = 0.06
    }
}

private const val CENTRAL_Z = 1.2816 // the 80 % central range the other searches report
internal const val TRUST_FLOOR = 0.005 // as in the fit: no bin is trusted better than this
private const val MIN_RISE = 1e-4
private const val MIN_SPREAD = 1e-9
private val MEDIAN_SE_FACTOR = sqrt(Math.PI / 2.0)

/** The spread of the first-reach error behind a bin's standard error (the error of a median is the spread over sqrt(n) times this factor). */
internal fun binSpread(point: GainPoint): Double = point.standardError * sqrt(point.count.toDouble()) / MEDIAN_SE_FACTOR

/**
 * The draft for a player whose rounds were played with [delivered] (the total gain of the curve at a hand speed in in/s) and
 * analysed with [analysis] (made with the same `delivered`). The estimate regresses what the errors say the hand wanted, divided by
 * the delivered gain, on the log gain of the curve: the slope is the strength minus one. The ceiling keeps the change of the gain,
 * `(strength - 1) * rise`, within [StrengthDraft.BANDWIDTH_Z] times the spread of the first-reach error of every speed bin between the
 * 5th and 95th percentile of the hand speeds. Null with too few bins, like the fit.
 */
fun draftStrength(analysis: AccelAnalysis, delivered: (Double) -> Double, z: Double = StrengthDraft.BANDWIDTH_Z): StrengthDraft? {
    val points = analysis.points
    if (points.size < AccelAnalysis.MIN_BINS) return null
    val slowGain = delivered(OwnCurveStrength.GRID_FROM)

    // The estimate: weighted least squares of (wanted log gain - delivered log gain) on the delivered log gain, with a free level.
    val x = points.map { ln(delivered(it.speed)) }
    val y = points.indices.map { points[it].logGain - x[it] }
    val w = points.map { 1.0 / (it.standardError * it.standardError + TRUST_FLOOR * TRUST_FLOOR) }
    val wSum = w.sum()
    val xBar = w.indices.sumOf { w[it] * x[it] } / wSum
    val yBar = w.indices.sumOf { w[it] * y[it] } / wSum
    val sxx = w.indices.sumOf { w[it] * (x[it] - xBar) * (x[it] - xBar) }
    val acts = sxx > MIN_SPREAD
    val estimate = if (acts) 1.0 + w.indices.sumOf { w[it] * (x[it] - xBar) * (y[it] - yBar) } / sxx else 1.0
    val standardError = if (acts) 1.0 / sqrt(sxx) else Double.POSITIVE_INFINITY

    // The ceiling: the change of the log gain against the curve, (s - 1) * rise(v), must stay within z error spreads in every common bin.
    val low = analysis.speedQuantile(0.05)
    val high = analysis.speedQuantile(0.95)
    val common = points.filter { it.speed in low..high }.ifEmpty { points }
    val room = common.mapNotNull { point ->
        val rise = abs(ln(delivered(point.speed) / slowGain))
        val spread = binSpread(point)
        if (rise < MIN_RISE) null else z * spread / rise
    }.minOrNull() ?: Double.POSITIVE_INFINITY
    val ceiling = min(OwnCurveStrength.MAX, 1.0 + room)
    val floor = maxOf(OwnCurveStrength.MIN, 1.0 - room)

    val span = abs(ln(delivered(high) / delivered(low)))
    val beyondTrend = acts && abs(estimate - 1.0) * span > StrengthDraft.TREND_ALLOWANCE
    val stronger = beyondTrend && estimate - CENTRAL_Z * standardError > CurveStrength.NEUTRAL
    val weaker = beyondTrend && estimate + CENTRAL_Z * standardError < CurveStrength.FALLING_MAX_HIGH
    val (strength, basis) = when {
        stronger -> min(estimate, ceiling) to DraftBasis.MEASURED_STRONGER
        weaker -> maxOf(estimate, floor) to DraftBasis.MEASURED_WEAKER
        else -> 1.0 + StrengthDraft.DESIGN_SHARE * (ceiling - 1.0) to DraftBasis.DESIGN
    }
    return StrengthDraft(estimate, standardError, ceiling, floor, strength, 1.0 + (strength - 1.0) / 2.0, basis,
        feltPlan(analysis, z).takeIf { basis != DraftBasis.MEASURED_WEAKER })
}
