package de.yuuto.aimtune.core.accel

import de.yuuto.aimtune.core.units.SearchRange
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * The blind strength search on top of the player's own curve (docs/FIND-CURVE-REDESIGN.md step 2). The strength `s` scales the rise
 * of the base curve `B` on the log scale: `C_s(v) = B(low) * (B(v) / B(low))^s`. One is the base itself, below one a weaker curve,
 * above one a stronger one; slow speeds keep the base's gain, so the sensitivity at rest is not touched and the question stays
 * "how much of this shape suits this hand". The search is one-dimensional like [CurveStrength]'s (same optimizer, same evidence
 * rules); only the range differs, as half to double of the base's rise, and the played curve is a lookup table.
 *
 * [base] is the profile as the driver runs it and [dpi] the DPI of the settings (the speeds the trainer plays at); a base with
 * another device DPI would act at other speeds and is refused before it gets here.
 */
class OwnCurveStrength(private val base: RawAccelProfile, private val dpi: Double) {
    internal val speeds = List(GRID_POINTS) { GRID_FROM * exp(ln(GRID_TO / GRID_FROM) * it / (GRID_POINTS - 1)) }
    internal val gains = speeds.map { base.planningGain(it, dpi) }
    private val slowGain = gains.first()
    private val logRise = gains.map { ln(it / slowGain) }

    /** The range the optimizer searches, as pseudo cm/360 (strength times 100, like [CurveStrength]). */
    val searchRange: SearchRange = SearchRange(MIN * PSEUDO_SCALE, MAX * PSEUDO_SCALE)

    /** What a round of [strength] plays: the base itself within [CurveStrength.PLAIN_BAND] of one, otherwise the scaled table. */
    fun model(strength: Double): AccelModel {
        require(strength > 0.0 && strength.isFinite()) { "strength must be positive" }
        if (!CurveStrength.isCurve(strength)) return base
        return LookupCurve(speeds, logRise.map { slowGain * exp(strength * it) })
    }

    /** The scaled table as a candidate for the blind test; the sensitivity factor stays 1, the table carries the level. */
    fun offer(strength: Double): AccelFit {
        require(CurveStrength.isCurve(strength)) { "a strength within ${CurveStrength.PLAIN_BAND} of 1 is no change of the curve" }
        return AccelFit(
            family = "your curve, strength x%.2f".format(java.util.Locale.ROOT, strength),
            curve = model(strength) as AccelCurve,
            sensitivityFactor = 1.0,
            chiSquare = 0.0,
            nullChiSquare = 0.0,
            bicGain = 0.0,
            gainRange = if (strength > 1.0) strength else 1.0 / strength,
        )
    }

    /**
     * The strength a played table stands for, or null when it is not one of this base's tables (another grid, another base, or a
     * curve of the error analysis). Read back from the stored round, as [CurveStrength] curves are.
     */
    fun strengthOf(curve: AccelCurve): Double? {
        if (curve !is LookupCurve || curve.speeds.size != GRID_POINTS) return null
        if (curve.speeds.indices.any { abs(curve.speeds[it] - speeds[it].toFloat().toDouble()) > speeds[it] * 1e-6 }) return null
        val estimates = curve.speeds.indices.filter { abs(logRise[it]) > MIN_LOG_RISE }.map { ln(curve.multipliers[it] / slowGain) / logRise[it] }
        if (estimates.isEmpty()) return null
        val s = estimates.sorted()[estimates.size / 2]
        return s.takeIf { s > 0.0 && estimates.all { e -> abs(e - s) < CONSISTENCY * maxOf(1.0, abs(s)) } }
    }

    companion object {
        /** Half to double the rise of the player's curve (symmetric on the log scale). */
        const val MIN = 0.5
        const val MAX = 2.0
        const val GRID_FROM = 0.5
        private const val GRID_TO = 200.0
        private const val GRID_POINTS = 64
        private const val PSEUDO_SCALE = 100.0

        /** Points where the base has not risen yet say nothing about the strength. */
        private const val MIN_LOG_RISE = 0.01
        private const val CONSISTENCY = 2e-3
    }
}
