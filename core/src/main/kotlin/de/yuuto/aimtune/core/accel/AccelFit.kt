package de.yuuto.aimtune.core.accel

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A Raw Accel curve that reproduces the wanted gain: the total gain at speed v is
 * `sensitivityFactor * curve.multiplier(v)`. Raw Accel curves start at 1, so a wanted gain that is
 * not 1 at low speed shows up as a factor for the in-game sensitivity: multiply it with
 * [sensitivityFactor] (equivalently divide cm/360 by it) and the average gain stays as it was.
 */
data class AccelFit(
    val family: String,
    val curve: AccelCurve,
    val sensitivityFactor: Double,
    /** Weighted squared misfit of this model, and of "no acceleration", over the speed bins. */
    val chiSquare: Double,
    val nullChiSquare: Double,
    /** BIC of "no acceleration" minus BIC of this model; above about 10 the curve is clearly better. */
    val bicGain: Double,
    /** Largest over smallest total gain across the measured speeds. */
    val gainRange: Double,
) {
    fun totalGain(speed: Double): Double = sensitivityFactor * curve.multiplier(speed)
}

/** Why the analysis ended as it did; the app turns it into a translated text, [AccelFitResult.reason] is the English one. */
enum class AccelReason { NOT_ENOUGH_MOVEMENTS, NARROW_SPEED_RANGE, NO_FIT, NOT_BETTER_THAN_NONE, SMALL_CHANGE, FITS }

data class AccelFitResult(
    val points: List<GainPoint>,
    val fits: List<AccelFit>,
    /** The curve to try, or null when there is too little data or no curve is clearly better than none. */
    val recommended: AccelFit?,
    val reason: String,
    val reasonCode: AccelReason,
    /** Number the reason's text mentions: movements needed, or the smallest worthwhile change in percent; 0 otherwise. */
    val reasonValue: Int = 0,
)

/**
 * Fits the Raw Accel families (classic, natural, power) to the wanted gain of an [AccelAnalysis]
 * with a free overall factor, and recommends a curve only when it beats "no acceleration" by
 * [minBicGain] BIC points and changes the gain by at least [minGainRange] across the measured speeds.
 * Synchronous, jump and lookup modes are not fitted. See docs/ALGORITHMS.md ("Acceleration").
 */
fun fitAccel(
    analysis: AccelAnalysis,
    minBicGain: Double = MIN_BIC_GAIN,
    minGainRange: Double = MIN_GAIN_RANGE,
    floor: Double = FLOOR,
): AccelFitResult {
    val points = analysis.points
    if (points.size < AccelAnalysis.MIN_BINS) {
        val needed = AccelAnalysis.MIN_BINS * AccelAnalysis.MIN_PER_BIN
        return AccelFitResult(points, emptyList(), null, "Not enough movements yet: about $needed are needed.", AccelReason.NOT_ENOUGH_MOVEMENTS, needed)
    }
    if (analysis.speedRange < MIN_SPEED_RANGE) {
        return AccelFitResult(points, emptyList(), null, "The movements were all at about the same speed, so no curve can be judged.", AccelReason.NARROW_SPEED_RANGE)
    }
    val weights = points.map { 1.0 / (it.standardError * it.standardError + floor * floor) }
    val ys = points.map { it.logGain }
    val nullChi = weights.indices.sumOf { weights[it] * ys[it] * ys[it] }
    val bicPenalty = ln(points.size.toDouble())

    val fits = FAMILIES.mapNotNull { family ->
        val best = family.starts.map { start ->
            NelderMead.minimize(start, step = START_STEP) { objective(family, it, points, weights, ys).first }
        }.minBy { it.value }
        val (chi, logK) = objective(family, best.point, points, weights, ys)
        val curve = family.build(best.point)
        val gains = points.map { exp(logK) * curve.multiplier(it.speed) }
        AccelFit(
            family = family.name,
            curve = curve,
            sensitivityFactor = exp(logK),
            chiSquare = chi,
            nullChiSquare = nullChi,
            bicGain = nullChi - (chi + (family.parameters + 1) * bicPenalty),
            gainRange = gains.max() / gains.min(),
        ).takeIf { it.chiSquare.isFinite() }
    }
    val best = fits.maxByOrNull { it.bicGain }
    return when {
        best == null -> AccelFitResult(points, fits, null, "No curve could be fitted.", AccelReason.NO_FIT)
        best.bicGain < minBicGain -> AccelFitResult(points, fits, null, "No curve fits clearly better than no acceleration.", AccelReason.NOT_BETTER_THAN_NONE)
        best.gainRange < minGainRange -> {
            val percent = ((minGainRange - 1) * 100).toInt()
            AccelFitResult(points, fits, null, "The best curve would change the gain by less than $percent %.", AccelReason.SMALL_CHANGE, percent)
        }
        else -> AccelFitResult(points, fits, best, "The ${best.family} curve fits clearly better than no acceleration.", AccelReason.FITS)
    }
}

/**
 * Thresholds for recommending a curve (docs/history/ASSESSMENT.md B1, ALGORITHMS.md "Detection power"). With the slow reaches
 * left out and a trust floor of 0.005 per bin, the best BIC gain of 1 600 simulated players without any preference
 * was at most 9.4 (99th percentile 5.6, only with noise x1.6); 8 leaves a margin, and the blind test stays the final
 * judge. The gain range of such players is up to 1.04, so the BIC gain carries the decision and the range only
 * excludes curves that would change nothing.
 */
const val MIN_BIC_GAIN = 8.0
const val MIN_GAIN_RANGE = 1.02

/** Slowest to fastest bin; a rising preference slows the hand, and the slowest reaches are not used (AccelAnalysis.MIN_SPEED). */
private const val MIN_SPEED_RANGE = 1.5

/**
 * A bin's wanted gain is never trusted to better than this (log scale), however many movements it has. It was 0.02 until the
 * assessment: that hid every effect under about 3 % (a 15 % preference moves the bins by 0.03-0.05); at 0.005 the
 * number of bins matters, since bins of fewer than about 20 movements have unreliable standard errors and the null grows heavy tails.
 */
private const val FLOOR = 0.005
private const val START_STEP = 0.5

private class Family(val name: String, val parameters: Int, val starts: List<DoubleArray>, val build: (DoubleArray) -> AccelCurve)

private val FAMILIES = listOf(
    Family("classic", 3, listOf(doubleArrayOf(ln(0.05), 2.0, ln(1.8)), doubleArrayOf(ln(0.15), 2.0, ln(0.7)), doubleArrayOf(ln(0.02), 2.5, ln(3.0)))) { t ->
        ClassicCurve(
            acceleration = exp(t[0].coerceIn(ln(0.005), ln(5.0))),
            exponent = t[1].coerceIn(1.1, 4.0),
            cap = exp(t[2].coerceIn(ln(0.3), ln(6.0))),
        )
    },
    Family("natural", 2, listOf(doubleArrayOf(ln(0.1), ln(1.6)), doubleArrayOf(ln(0.2), ln(0.7)), doubleArrayOf(ln(0.05), ln(2.5)))) { t ->
        val limit = exp(t[1].coerceIn(ln(0.3), ln(6.0)))
        NaturalCurve(
            decayRate = exp(t[0].coerceIn(ln(0.01), ln(3.0))),
            // A limit of exactly 1 is no curve at all and Raw Accel's formula divides by it.
            limit = if (limit in 0.98..1.02) 1.02 else limit,
        )
    },
    Family("power", 2, listOf(doubleArrayOf(ln(0.1), 0.3), doubleArrayOf(ln(0.05), 0.6))) { t ->
        PowerCurve(scale = exp(t[0].coerceIn(ln(0.005), ln(2.0))), exponent = t[1].coerceIn(0.05, 1.5), outputOffset = 1.0)
    },
)

/** Weighted misfit of a family at [params] with the best overall factor, and that factor's log. */
private fun objective(family: Family, params: DoubleArray, points: List<GainPoint>, weights: List<Double>, ys: List<Double>): Pair<Double, Double> {
    val curve = family.build(params)
    val residual = points.indices.map { ys[it] - ln(max(curve.multiplier(points[it].speed), MIN_MULTIPLIER)) }
    // The overall factor is bounded: the curve under test must not smuggle in a different sensitivity (docs/history/ASSESSMENT.md B2).
    val logK = (weights.indices.sumOf { weights[it] * residual[it] } / weights.sum()).coerceIn(-MAX_LOG_FACTOR, MAX_LOG_FACTOR)
    val chi = weights.indices.sumOf { weights[it] * (residual[it] - logK).let { d -> d * d } }
    return chi to logK
}

private const val MIN_MULTIPLIER = 1e-3
/** The fitted sensitivity factor stays within about +-10 %. */
private val MAX_LOG_FACTOR = ln(1.1)

/**
 * The fit as a Raw Accel lookup table over the speeds the data measured, [low] to [high] in/s ([LOOKUP_POINTS] points,
 * evenly spaced in log speed), and flat outside them. A family fitted to a narrow window keeps rising or falling beyond it,
 * where the data say nothing: fitted over 4.5-8.3 in/s, a +15 % preference was exported as x1.38 at 40 in/s against a true
 * x1.06 (the removed docs/OPTIMIZER-REVIEW.md (git history), E2). Holding the last measured value is the least that can be said there.
 */
fun AccelFit.boundedTo(low: Double, high: Double, points: Int = LOOKUP_POINTS): AccelFit {
    require(low > 0.0 && high > low) { "need 0 < low < high" }
    require(points in 2..LookupCurve.MAX_POINTS) { "points must be in 2..${LookupCurve.MAX_POINTS}" }
    val speeds = (0 until points).map { low * exp(ln(high / low) * it / (points - 1)) }
    return copy(family = "$family (table)", curve = LookupCurve(speeds, speeds.map { curve.multiplier(it) }))
}

/**
 * This fit on top of a curve the rounds were played with (docs/FIND-CURVE-REDESIGN.md step 1). The analysis divides that curve out
 * and keeps only the wanted shape, centred at the median speed, so the fit starts at gain 1 there; [level] is the gain the base
 * delivers at that speed, and the player's sensitivity is taken to be right at the typical speed. The level goes into a lookup
 * table (a family cannot start above 1), over the speeds of a table fit or, for a family, a log grid from [FROM] to [TO] in/s.
 */
fun AccelFit.scaledBy(level: Double): AccelFit {
    require(level > 0.0 && level.isFinite()) { "level must be positive" }
    val table = curve as? LookupCurve
    val speeds = table?.speeds ?: (0 until LOOKUP_POINTS).map { LEVEL_GRID_FROM * exp(ln(LEVEL_GRID_TO / LEVEL_GRID_FROM) * it / (LOOKUP_POINTS - 1)) }
    return copy(family = "$family (on your curve)", curve = LookupCurve(speeds, speeds.map { curve.multiplier(it) * level }))
}

private const val LEVEL_GRID_FROM = 0.5
private const val LEVEL_GRID_TO = 200.0

/**
 * The recommendation of [result] bounded to the middle [BOUND_SHARE] of the measured speeds of [analysis]
 * (5th to 95th percentile), or null when there is none. The detection itself is unchanged. A curve is only recommended
 * for a speed range of at least 1.5 between the bins, so the percentiles are always apart; should they not be (under
 * [MIN_BOUND_RATIO]), there is no range to bound to and the fit is returned as it is.
 */
fun boundedRecommendation(result: AccelFitResult, analysis: AccelAnalysis): AccelFit? {
    val fit = result.recommended ?: return null
    val low = analysis.speedQuantile((1.0 - BOUND_SHARE) / 2)
    val high = analysis.speedQuantile((1.0 + BOUND_SHARE) / 2)
    return if (low > 0.0 && high >= low * MIN_BOUND_RATIO) fit.boundedTo(low, high) else fit
}

const val MIN_BOUND_RATIO = 1.1

/** This result with its recommendation bounded to the measured speeds of [analysis] (curve finder v2, M14 T3). */
fun AccelFitResult.bounded(analysis: AccelAnalysis): AccelFitResult = copy(recommended = boundedRecommendation(this, analysis))

const val LOOKUP_POINTS = 32
const val BOUND_SHARE = 0.9

/**
 * A mild curve for a player who wants to try one although the analysis found none: the gain moves by
 * [strength] (15 % by default) from slow to fast hands, upward or downward, and the low-speed sensitivity stays
 * as it is. It is a hypothesis, not a finding; the blind test decides if it is worth keeping.
 */
fun trialAccel(rising: Boolean, strength: Double = TRIAL_STRENGTH): AccelFit {
    require(strength > 0.0 && strength < 0.9) { "strength must be between 0 and 0.9" }
    val limit = if (rising) 1.0 + strength else 1.0 - strength
    return AccelFit(
        family = "natural (trial)",
        curve = NaturalCurve(decayRate = TRIAL_DECAY, limit = limit),
        sensitivityFactor = 1.0,
        chiSquare = 0.0,
        nullChiSquare = 0.0,
        bicGain = 0.0,
        gainRange = if (rising) limit else 1.0 / limit,
    )
}

const val TRIAL_STRENGTH = 0.15
private const val TRIAL_DECAY = 0.05
