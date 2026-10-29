package de.yuuto.aimtune.core.accel

import de.yuuto.aimtune.core.units.SearchRange
import kotlin.math.abs

/**
 * The two things "Refine my Raw Accel curve" changes about a profile the player imported, one at a time, each as a factor
 * around 1 (1 = the imported value). A factor is searched blind over [min]..[max] on a log scale, like [CurveStrength]: the
 * optimizer sees a pseudo cm/360 of `100 * factor`. Refine never changes the base of a profile (sensitivity, DPI, ratios,
 * rotation, the multiplier at very low speed, ...); only the curve-shape fields of [RefineShape] move.
 *
 * - [HIGH_GAIN]: how much the curve adds at high hand speed. Classic: `capY - 1` times s (cap modes `in_out` and `output`)
 *   or, without a cap output (`input` mode, or `output` with cap 0), the acceleration times `s^(1/(exponent-1))`, which
 *   scales the output that is reached at the cap by s. Natural: `limit - 1` times s, with the decay rate times s as well,
 *   because Raw Accel's natural curve derives its speed scale from `decayRate / |limit - 1|`, and the curve should rise as
 *   fast as before, only to another height. Lut: the rise of the multiplier above its value at the first point times s
 *   ([RefineLut]). Jump: `capY - 1` times s, for a hard step only.
 * - [TRANSITION]: where the curve starts to rise. Classic: the input offset times t; with an input cap
 *   (`in_out`, `input`) the cap point moves with the offset when the offset grows (`capX * t`), because Raw Accel
 *   refuses a cap below the offset and the rise should keep its shape. Natural: the decay rate divided by t (t > 1 is a
 *   slower, more gradual approach to the limit). Classic with offset 0 has no transition to move. Lut: the speed axis
 *   stretched by t from the first point. Jump: `capX` times t. Synchronous: `syncSpeed` times t.
 *
 * Power and noaccel have no stage. By-component profiles apply the factor to both axes.
 */
enum class RefineParameter(val min: Double, val max: Double) {
    HIGH_GAIN(0.5, 2.0),
    TRANSITION(0.5, 2.0),
    ;

    /** The range the optimizer searches, as pseudo cm/360. */
    val searchRange: SearchRange get() = SearchRange(min * PSEUDO_SCALE, max * PSEUDO_SCALE)

    fun toPseudo(factor: Double): Double = factor * PSEUDO_SCALE

    fun fromPseudo(pseudoCm360: Double): Double = pseudoCm360 / PSEUDO_SCALE

    /** True when [factor] is far enough from 1 to be played as a change. */
    fun isChange(factor: Double): Boolean = abs(factor - NEUTRAL) >= NEUTRAL_BAND

    /**
     * True when the credible range [low]..[high] of the best [factor] clearly excludes 1 (no change). Both directions need
     * the same evidence here: unlike the strength search there is a curve to compare with, not the plain mouse.
     */
    fun excludesNeutral(factor: Double, low: Double, high: Double): Boolean = isChange(factor) && (low > NEUTRAL || high < NEUTRAL)

    companion object {
        const val NEUTRAL = 1.0

        /** Factors closer to 1 than this are no change. */
        const val NEUTRAL_BAND = 0.02
        private const val PSEUDO_SCALE = 100.0
    }
}

