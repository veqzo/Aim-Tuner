package dev.veqzo.aimtune.core.sim

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * How the hand's mouse reports deviate from the movement the player means, in the Raw Accel sign conventions (counts, x right,
 * y down), so that the matching Raw Accel setting undoes the deviation exactly. The default is a hand without any (neutral): the
 * aimer then skips [distort] and every result stays what it was before these parameters existed.
 *
 * With intended counts (ix, iy) the hand produces, in this order (the inverse of `rawaccel.hpp:322`, `:414`, `:416-422`):
 *  1. left: `ix < 0` gives `ix / leftBias`; up: `iy < 0` gives `iy / upBias` (Raw Accel's L/R and U/D ratios);
 *  2. vertical gain: `iy / verticalGain` (Raw Accel's Y/X ratio);
 *  3. grip rotation: the vector is rotated by `-gripRotationDegrees` (Raw Accel's rotation of +theta rotates it back,
 *     `x' = x cos - y sin`, `y' = x sin + y cos`, `math-vec2.hpp:20-26`).
 *
 * So `verticalGain` below 1 is a hand that moves too far vertically (Y/X = verticalGain corrects it), `leftBias` below 1 a hand
 * that moves too far to the left, and a positive `gripRotationDegrees` a hand whose movements come out turned by
 * `-gripRotationDegrees` (the setting to enter is `+gripRotationDegrees`).
 *
 * [driftSlopeSd] is a different kind of deviation, for the angle snapping: every first reach gets a vertical drift of
 * `slope * horizontal hand movement` with `slope ~ N(0, driftSlopeSd)` (a slightly tilted path, as people make when they flick
 * sideways). The player does not plan with it; it sees the result and corrects it, like any other error.
 */
data class HandGeometry(
    val gripRotationDegrees: Double = 0.0,
    val verticalGain: Double = 1.0,
    val leftBias: Double = 1.0,
    val upBias: Double = 1.0,
    val driftSlopeSd: Double = 0.0,
) {
    init {
        require(verticalGain > 0.0 && leftBias > 0.0 && upBias > 0.0) { "gains must be positive" }
        require(driftSlopeSd >= 0.0) { "drift must not be negative" }
    }

    /** True when the hand's counts are deviated (the drift acts on the hand position, not on counts). */
    val distortsCounts: Boolean get() = gripRotationDegrees != 0.0 || verticalGain != 1.0 || leftBias != 1.0 || upBias != 1.0

    /** The (continuous) counts the hand produces to deliver [intendedX] and [intendedY]. */
    fun distort(intendedX: Double, intendedY: Double): Pair<Double, Double> {
        val x = if (intendedX < 0.0) intendedX / leftBias else intendedX
        var y = intendedY / verticalGain
        if (intendedY < 0.0) y /= upBias
        val radians = -gripRotationDegrees * PI / 180
        return (x * cos(radians) - y * sin(radians)) to (x * sin(radians) + y * cos(radians))
    }

    companion object {
        val NEUTRAL = HandGeometry()
    }
}
