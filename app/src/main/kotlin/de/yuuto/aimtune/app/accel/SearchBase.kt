package de.yuuto.aimtune.app.accel

import de.yuuto.aimtune.core.accel.AccelSpec
import de.yuuto.aimtune.core.accel.OwnCurveStrength
import de.yuuto.aimtune.core.accel.RawAccelProfile

/**
 * The player's own Raw Accel curve a strength search is played on top of (docs/FIND-CURVE-REDESIGN.md step 2): the search's
 * curves ([strengths]) and the stored text of the curve itself ([spec]), which is what a round of strength 1 stores.
 * No base anywhere in the search code means a plain mouse and the natural curves of [de.yuuto.aimtune.core.accel.CurveStrength].
 */
class SearchBase(val profile: RawAccelProfile, dpi: Double) {
    val strengths = OwnCurveStrength(profile, dpi)
    val spec: String = AccelSpec.encodeModel(profile)
}
