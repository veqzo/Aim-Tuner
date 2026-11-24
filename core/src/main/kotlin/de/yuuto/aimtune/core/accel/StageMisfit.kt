package de.yuuto.aimtune.core.accel

/**
 * How far the gain of a played stage is from what the hand asks for, fast against slow (docs/FIND-CURVE-REDESIGN.md section 13).
 * [contrast] is negative when the hand wants less gain at the fast end than the stage gives it (not yet used to the step, or the
 * step is too big) and about zero when the hand wants what it is given; [standardError] is its uncertainty.
 */
data class StageMisfit(val contrast: Double, val standardError: Double)

/**
 * The misfit of [analysis] (made with `delivered` = the stage's gain) against [delivered], contrasting the bins by their weight in the
 * step: `D_i` is the wanted log gain minus the delivered one, and the contrast is the weighted mean of `D` on the fast side minus the
 * one on the slow side, so the level and the centring of the analysis drop out. Only bins between the 5th and 95th percentile of the
 * speeds count. Null when the speeds do not reach both sides.
 */
fun stageMisfit(analysis: AccelAnalysis, delivered: (Double) -> Double, anchorSlow: Double, anchorFast: Double): StageMisfit? {
    val low = analysis.speedQuantile(0.05)
    val high = analysis.speedQuantile(0.95)
    val bins = analysis.points.filter { it.speed in low..high }
    if (bins.size < AccelAnalysis.MIN_BINS) return null
    val fastWeight = bins.map { FeltStep.weight(it.speed, anchorSlow, anchorFast) }
    val fastSum = fastWeight.sum()
    val slowSum = fastWeight.sumOf { 1.0 - it }
    if (fastSum <= 0.0 || slowSum <= 0.0) return null
    var contrast = 0.0
    var variance = 0.0
    for ((i, point) in bins.withIndex()) {
        val a = fastWeight[i] / fastSum - (1.0 - fastWeight[i]) / slowSum
        contrast += a * (point.logGain - kotlin.math.ln(delivered(point.speed)))
        variance += a * a * (point.standardError * point.standardError + TRUST_FLOOR * TRUST_FLOOR)
    }
    return StageMisfit(contrast, kotlin.math.sqrt(variance))
}
