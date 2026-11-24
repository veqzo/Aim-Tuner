package dev.veqzo.aimtune.core.accel.settings

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Raw Accel's rotation from the direction of first movements (docs/ALGORITHMS.md, "Passive setting analyses"). Per reach the signed
 * angle phi from the direction to the target to the direction of the first movement is measured in counts (x right, y down, the
 * frame of `rawaccel.hpp:322`, where a rotation by +theta adds theta to the angle of a vector). A hand whose movements come out
 * turned by phi is corrected by a rotation of -phi, so the estimate is `-median(phi)`.
 *
 * Thresholds, fixed before the gate ran: at least [MIN_REACHES] reaches; interval = 95 % bootstrap of the median; recommend only
 * if the interval excludes 0 and the estimate is at least [MIN_ANGLE_DEGREES]; the value is rounded to 0.5 degrees.
 */
object RotationAnalysis {
    const val MIN_REACHES = 60
    const val MIN_ANGLE_DEGREES = 1.0
    private const val ROUNDING_STEP = 0.5

    fun analyze(reaches: List<FirstReach>): PassiveResult {
        val angles = reaches.filter { it.gotX != 0.0 || it.gotY != 0.0 }.map(::angleDegrees)
        if (angles.size < MIN_REACHES) return PassiveResult.NotEnoughData(angles.size, MIN_REACHES)
        val estimate = -angles.median()
        val (a, b) = bootstrapInterval(angles) { it.median() }
        val low = -b
        val high = -a
        val excludesZero = low > 0.0 || high < 0.0
        if (!excludesZero || abs(estimate) < MIN_ANGLE_DEGREES) return PassiveResult.NoChange(estimate, low, high, angles.size)
        return PassiveResult.Recommend((estimate / ROUNDING_STEP).roundToInt() * ROUNDING_STEP, low, high, angles.size)
    }

    /** Angle of the first movement relative to the direction to the target in degrees, positive from x toward y (down). */
    internal fun angleDegrees(reach: FirstReach): Double {
        val cross = reach.needX * reach.gotY - reach.needY * reach.gotX
        val dot = reach.needX * reach.gotX + reach.needY * reach.gotY
        return Math.toDegrees(atan2(cross, dot))
    }
}
