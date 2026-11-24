package dev.veqzo.aimtune.core.accel

import dev.veqzo.aimtune.core.units.SearchRange
import kotlin.math.abs

/**
 * One acceleration curve family with a single parameter, its strength: the gain at high hand speed
 * relative to low speed (1 = no curve, 1.2 = 20 % more sensitivity for fast movements, 0.8 = 20 % less).
 * The curve is Raw Accel's natural mode, which starts at gain 1 and levels off by itself; its decay is fixed.
 *
 * The strength is searched blind with the same optimizer that finds the sensitivity, over a pseudo
 * cm/360 of `100 * strength` (so the search is on a log scale and symmetric in rising and falling), instead
 * of being inferred from how first movements miss: that inference sees only a narrow band of hand speeds
 * (docs/history/ASSESSMENT.md, "Follow-up on a more precise curve").
 */
object CurveStrength {
    const val MIN = 0.7
    const val MAX = 1.45

    /**
     * The upper end when the search plays flash rounds. A faster turn away from a flash pays for more gain at speed, and
     * with flash rounds the whole chain (search, then blind test) reached more players who gain from a curve and exported
     * no harmful one more often (chain-strength-search-flash35/15.txt, M14 follow-up). Without flash rounds a wider range
     * only overshot moderate preferences (assess-strength-range.txt), so it stays at [MAX] there.
     */
    const val MAX_WITH_FLASH = 1.75

    /** The strength of "no curve". */
    const val NEUTRAL = 1.0

    /** Strengths closer to 1 than this are played as no curve at all. */
    const val PLAIN_BAND = 0.02

    /** The decay rate of the natural curves the search plays; stored rounds are recognised by it. */
    const val DECAY = 0.05

    /**
     * A falling strength counts as clear only when the upper end of its credible range is below this. Falling curves are
     * the ones the search found on noise most often (docs/history/CURVE-FINDER-AUDIT.md, option O2), so they need more evidence
     * than rising ones, whose range only has to stay above 1.
     */
    const val FALLING_MAX_HIGH = 0.90
    private const val PSEUDO_SCALE = 100.0

    /** The range the optimizer searches without flash rounds, as pseudo cm/360. */
    val searchRange = SearchRange(MIN * PSEUDO_SCALE, MAX * PSEUDO_SCALE)

    /** The range the optimizer searches, as pseudo cm/360: up to [MAX_WITH_FLASH] when the search plays flash rounds. */
    fun searchRange(flash: Boolean): SearchRange = if (flash) SearchRange(MIN * PSEUDO_SCALE, MAX_WITH_FLASH * PSEUDO_SCALE) else searchRange

    fun toPseudo(strength: Double): Double = strength * PSEUDO_SCALE

    fun fromPseudo(pseudoCm360: Double): Double = pseudoCm360 / PSEUDO_SCALE

    /** The curve of [strength], or null for "no curve" when it is within [PLAIN_BAND] of 1. */
    fun curve(strength: Double): AccelCurve? {
        require(strength > 0.0 && strength.isFinite()) { "strength must be positive" }
        return if (abs(strength - NEUTRAL) < PLAIN_BAND) null else NaturalCurve(DECAY, strength)
    }

    /** True when [strength] would be played as a real curve. */
    fun isCurve(strength: Double): Boolean = abs(strength - NEUTRAL) >= PLAIN_BAND

    /**
     * True when the credible range [low]..[high] of the best [strength] clearly excludes "no curve": a real curve whose
     * range is above 1 (rising), or entirely below [FALLING_MAX_HIGH] (falling).
     */
    fun excludesPlain(strength: Double, low: Double, high: Double): Boolean {
        if (!isCurve(strength)) return false
        return low > NEUTRAL || high < FALLING_MAX_HIGH
    }
}

/** The curve of [strength] as a candidate for the blind A/B test; the sensitivity factor stays 1 (the search included it). */
fun strengthAccel(strength: Double): AccelFit {
    require(CurveStrength.isCurve(strength)) { "a strength within ${CurveStrength.PLAIN_BAND} of 1 is no curve" }
    val curve = checkNotNull(CurveStrength.curve(strength))
    return AccelFit(
        family = "natural (strength search)",
        curve = curve,
        sensitivityFactor = 1.0,
        chiSquare = 0.0,
        nullChiSquare = 0.0,
        bicGain = 0.0,
        gainRange = if (strength > 1.0) strength else 1.0 / strength,
    )
}
