package de.yuuto.aimtune.app.ui

import com.badlogic.gdx.graphics.Color
import de.yuuto.aimtune.app.blind.ScoredRound
import de.yuuto.aimtune.core.accel.AccelFit
import de.yuuto.aimtune.core.accel.GainPoint
import de.yuuto.aimtune.core.optimizer.OptimizationResult
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

private const val SCORE_MAX = 100.0
private const val BAND_Z = 1.96
private const val BAND_ALPHA = 0.28f
private const val CURVE_POINTS = 60
private const val GAIN_MARGIN = 0.08

/** Score against cm/360°: the fitted mean, its 95 % band, the round scores and the recommended value. */
fun sensitivityChart(tokens: Tokens, result: OptimizationResult, rounds: List<ScoredRound>): ChartSpec {
    val curve = result.curve
    val xs = DoubleArray(curve.size) { curve[it].cm360 }
    val mean = DoubleArray(curve.size) { curve[it].meanScore }
    val band = ChartBand(
        xs,
        DoubleArray(curve.size) { curve[it].meanScore - BAND_Z * curve[it].sdScore },
        DoubleArray(curve.size) { curve[it].meanScore + BAND_Z * curve[it].sdScore },
        Color(tokens.accent.r, tokens.accent.g, tokens.accent.b, BAND_ALPHA),
    )
    val scored = rounds.filter { it.score != null }
    return ChartSpec(
        xMin = xs.first(),
        xMax = xs.last(),
        yMin = 0.0,
        yMax = SCORE_MAX,
        yTicks = listOf(0.0, 25.0, 50.0, 75.0, 100.0),
        bands = listOf(band),
        lines = listOf(ChartLine(xs, mean, tokens.accent(300))),
        dots = listOf(ChartDots(DoubleArray(scored.size) { scored[it].cm360 }, DoubleArray(scored.size) { checkNotNull(scored[it].score) }, tokens.chartBaseline)),
        markers = listOf(ChartMarker(result.optimumCm360, "%.1f".format(Locale.ROOT, result.optimumCm360), tokens.text)),
    )
}

/**
 * Gain against hand speed in in/s: the gain the player wanted per speed bin (dots) and the fitted
 * curve (line), both relative to their average over the measured movements so they are comparable.
 */
fun accelChart(tokens: Tokens, points: List<GainPoint>, fit: AccelFit?): ChartSpec {
    require(points.size >= 2) { "a chart needs at least two speed bins" }
    val speeds = points.map { it.speed }
    val lo = speeds.min()
    val hi = speeds.max()
    val xs = DoubleArray(CURVE_POINTS) { lo + (hi - lo) * it / (CURVE_POINTS - 1) }
    val fitted = fit?.let { normalizedCurve(it, points, xs) }
    val values = points.map { it.gain } + (fitted?.toList() ?: emptyList())
    val yMin = values.min() - GAIN_MARGIN
    val yMax = values.max() + GAIN_MARGIN
    return ChartSpec(
        xMin = lo,
        xMax = hi,
        yMin = yMin,
        yMax = yMax,
        bands = listOf(
            ChartBand(
                DoubleArray(points.size) { points[it].speed },
                DoubleArray(points.size) { exp(points[it].logGain - BAND_Z * points[it].standardError) },
                DoubleArray(points.size) { exp(points[it].logGain + BAND_Z * points[it].standardError) },
                Color(tokens.chartBaseline.r, tokens.chartBaseline.g, tokens.chartBaseline.b, BAND_ALPHA),
            ),
        ),
        lines = listOfNotNull(fitted?.let { ChartLine(xs, it, tokens.chartCandidate) }),
        dots = listOf(ChartDots(DoubleArray(points.size) { points[it].speed }, DoubleArray(points.size) { points[it].gain }, tokens.chartBaseline)),
        referenceYs = listOf(1.0),
    )
}

/** The fit's total gain at [xs], divided by its count-weighted geometric mean over the bins (the analysis uses the same normalization). */
private fun normalizedCurve(fit: AccelFit, points: List<GainPoint>, xs: DoubleArray): DoubleArray {
    val total = points.sumOf { it.count }.toDouble()
    val mean = exp(points.sumOf { ln(fit.totalGain(it.speed)) * it.count } / total)
    return DoubleArray(xs.size) { fit.totalGain(xs[it]) / mean }
}

private const val PROFILE_CHART_MAX_SPEED = 120.0
private const val PROFILE_CHART_POINTS = 120

/** The total multiplier of a Raw Accel profile (curve times output DPI / device DPI) against hand speed in in/s, with the line at 1. */
fun profileChart(tokens: Tokens, profile: de.yuuto.aimtune.core.accel.RawAccelProfile): ChartSpec = multiplierChart(tokens, profile::totalMultiplier)

/** The total gain of a found or offered curve against hand speed in in/s, with the line at 1; for curves without measured speed bins. */
fun fitChart(tokens: Tokens, fit: AccelFit): ChartSpec = multiplierChart(tokens, fit::totalGain)

private fun multiplierChart(tokens: Tokens, multiplier: (Double) -> Double): ChartSpec {
    val xs = DoubleArray(PROFILE_CHART_POINTS) { PROFILE_CHART_MAX_SPEED * (it + 1) / PROFILE_CHART_POINTS }
    val ys = DoubleArray(xs.size) { multiplier(xs[it]) }
    val low = minOf(ys.min(), 1.0)
    val high = maxOf(ys.max(), 1.0)
    val margin = maxOf((high - low) * 0.15, 0.05)
    return ChartSpec(
        xMin = 0.0, xMax = PROFILE_CHART_MAX_SPEED, yMin = low - margin, yMax = high + margin,
        lines = listOf(ChartLine(xs, ys, tokens.chartCandidate)), referenceYs = listOf(1.0),
    )
}
