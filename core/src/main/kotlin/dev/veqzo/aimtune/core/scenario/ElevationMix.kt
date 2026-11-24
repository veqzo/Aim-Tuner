package dev.veqzo.aimtune.core.scenario

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Where enemies appear vertically, as an angle above or below the crosshair-level view. Valorant maps are
 * mostly flat and enemies stand where the crosshair is placed, so most targets sit at head height
 * (a narrow spread around the level), and a small share is above or below (heaven positions, ramps,
 * stairs, crouching players). [uniform] is the old trainer's fully 3D field (every pitch in the field
 * equally likely); it exists to compare results against the realistic mix, not for use.
 */
data class ElevationMix(
    /** Standard deviation in degrees of the pitch around head level for the bulk of the targets. */
    val spreadDegrees: Double = 1.5,
    /** Share of targets on a different level than the player. */
    val offLevelShare: Double = 0.12,
    /** How far off level, in degrees, those targets are (their magnitude). */
    val offLevelDegrees: PositiveRange = PositiveRange(3.0, 10.0),
    /** Share of the off-level targets that are below the player (the rest are above). */
    val loweredShare: Double = 0.4,
    val uniform: Boolean = false,
) {
    init {
        require(spreadDegrees in 0.0..20.0) { "spread must be in 0..20 degrees" }
        require(offLevelShare in 0.0..1.0 && loweredShare in 0.0..1.0) { "shares must be in 0..1" }
        require(offLevelDegrees.max <= 45.0) { "off-level targets beyond 45 degrees are not realistic" }
    }

    /** A pitch in degrees for a target whose head is at [levelPitch] in the middle, never beyond ±[fieldPitch]. */
    fun draw(random: Random, levelPitch: Double, fieldPitch: Double): Double {
        if (uniform) return random.nextDouble(-fieldPitch, fieldPitch)
        val pitch = if (random.nextDouble() < offLevelShare) {
            val sign = if (random.nextDouble() < loweredShare) -1.0 else 1.0
            levelPitch + sign * offLevelDegrees.uniform(random)
        } else {
            levelPitch + gaussian(random) * spreadDegrees
        }
        return pitch.coerceIn(-fieldPitch, fieldPitch)
    }

    companion object {
        /** The fully 3D field of the first trainer versions. */
        val UNIFORM = ElevationMix(uniform = true)

        /** Box-Muller; kotlin.random has no normal distribution. */
        private fun gaussian(random: Random): Double =
            sqrt(-2.0 * ln(1.0 - random.nextDouble())) * cos(2.0 * PI * random.nextDouble())
    }
}
