package de.yuuto.aimtune.core.scenario

/**
 * How a strafing opponent of the game moves (M23): each leg it holds a strafe key for [travelMeters] of way, lets go, and
 * stands for [dwellSeconds] before the next leg. The speed is not a parameter: it comes from the game's [Locomotion] (the
 * player accelerates to its top speed and brakes when it lets go), so short legs are slower on average than long ones.
 * [reverseProbability] is the chance to turn around after a stop, [maxOffsetMeters] how far the opponent may get from its
 * spawn. The profile's `strafe` block names the source of each value.
 */
data class StrafePattern(
    val travelMeters: PositiveRange,
    val dwellSeconds: PositiveRange,
    val reverseProbability: Double,
    val maxOffsetMeters: Double,
    val gait: Gait = Gait.RUN,
    val verified: Boolean = false,
) {
    init {
        require(reverseProbability in 0.0..1.0) { "reverse probability must be in 0..1" }
        require(maxOffsetMeters > 0.0 && travelMeters.max <= 2.0 * maxOffsetMeters) { "a leg must fit into the offset range" }
        require(dwellSeconds.max <= MAX_DWELL_SECONDS) { "dwell must be at most $MAX_DWELL_SECONDS s" }
    }

    private companion object {
        const val MAX_DWELL_SECONDS = 5.0
    }
}
