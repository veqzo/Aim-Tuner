package de.yuuto.aimtune.core.scenario

import de.yuuto.aimtune.core.score.DuelRules
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/** A closed interval of positive numbers, sampled uniformly or log-uniformly. */
data class PositiveRange(val min: Double, val max: Double) {
    init {
        require(min.isFinite() && max.isFinite() && min > 0.0 && max >= min) { "need 0 < min <= max, was $min..$max" }
    }

    fun uniform(random: Random): Double = if (max == min) min else random.nextDouble(min, max)

    /** Log-uniform: small and large values are equally likely per factor, like targets at evenly spread ranges. */
    fun logUniform(random: Random): Double = if (max == min) min else exp(random.nextDouble(ln(min), ln(max)))

    val mid: Double get() = kotlin.math.sqrt(min * max)
}

/** Targets whose angle to the previous target is at most [maxDegrees] (and above the previous bucket) are drawn with probability [weight]. */
data class AngleBucket(val maxDegrees: Double, val weight: Double) {
    init {
        require(maxDegrees > 0.0 && maxDegrees <= 180.0 && weight >= 0.0 && weight.isFinite()) { "invalid angle bucket" }
    }
}

/**
 * How realistic a game's engagements are, as the trainer needs them. Sizes are angles (degrees at the
 * game's FOV), never world or pixel sizes: the scene derives the sphere radius from the angle and the
 * distance, so a change of distance cannot change the difficulty. All values of a profile that were
 * not checked against the game have [verified] false and say so in the profile's notes.
 *
 * The defaults are the Valorant assumptions of docs/history/REVIEW.md ("Realism"): a head of about 0.2 m seen
 * from 6-30 m, mostly small corrections near the crosshair with occasional large flicks.
 */
data class TrainerGeometry(
    /** Half-width and half-height of the field targets appear in, in degrees from the starting view. */
    val fieldYawDegrees: Double = 50.0,
    val fieldPitchDegrees: Double = 25.0,
    /** Angular diameter of a click target (a head), drawn log-uniformly. */
    val clickTargetDiameterDegrees: PositiveRange = PositiveRange(0.4, 1.9),
    /** Distance the click targets are drawn at; only the depth of the scene, the angular size is what counts. */
    val clickTargetDistanceMeters: PositiveRange = PositiveRange(6.0, 30.0),
    /**
     * Measured engagement distances (M23). When set, together with [body], a click target is drawn at a distance from
     * this curve and is a head of [AgentBody.headDiameterMeters]: its angular size follows from the distance
     * (2 atan(r / d)), and [clickTargetDiameterDegrees] and [clickTargetDistanceMeters] are not used.
     */
    val clickTargetDistanceQuantiles: QuantileCurve? = null,
    /** Angle from the previous target (where the crosshair is after a hit) for flick targets. */
    val flickAngles: List<AngleBucket> = DEFAULT_FLICK_ANGLES,
    /** Angle from the previous target for micro-adjust targets. */
    val microStepDegrees: PositiveRange = PositiveRange(1.5, 8.0),
    /** Smallest angle between two visible targets (switching) and between a new target and the one just hit. */
    val switchingSeparationDegrees: Double = 10.0,
    /** Angular diameter of the tracked target (a body seen from the tracking distance). */
    val trackingDiameterDegrees: PositiveRange = PositiveRange(2.0, 3.7),
    val trackingDistanceMeters: Double = 14.0,
    /** Tangential speed of a strafing player; the angular speed follows from the tracking distance. */
    val strafeSpeedMetersPerSecond: PositiveRange = PositiveRange(3.0, 6.0),
    /** Share of tracking segments in which the target stops (a counter-strafe) before the next move. */
    val stopProbability: Double = 0.25,
    /** The flash turn-away scenario: popping time, positions, blind duration and the enemy that appears afterwards. */
    val flash: FlashGeometry = FlashGeometry(),
    /** Camera height above the floor and the height of the middle of an enemy's head; their difference tilts level targets slightly with distance. */
    val eyeHeightMeters: Double = 1.6,
    val headHeightMeters: Double = 1.65,
    /** Vertical distribution of the targets; [fieldPitchDegrees] stays the hard limit. */
    val elevation: ElevationMix = ElevationMix(),
    /** The minimal map (floor, wall, corners, doorways) and how often a target stands at a corner or doorway. */
    val arena: ArenaLayout = ArenaLayout(),
    /** Range mode of the game; null when the profile has no `range` block, and the game then has no Range mode. */
    val range: RangeGeometry? = null,
    /** Size and speed of a player of the game; null when the profile has no `body` block. */
    val body: AgentBody? = null,
    /** Ground acceleration and friction of a player of the game; null when the profile has no `movement` block. */
    val movement: GroundMovement? = null,
    /** How strafing click targets move with the game's [locomotion]; null keeps the constant-speed strafe of [StrafeConfig]. */
    val strafe: StrafePattern? = null,
    /** How often the game's engagement types occur; null lets the metric weights decide (see [ScenarioSchedule]). */
    val mix: ScenarioMix? = null,
    /** How the game's duels are decided; with them both finders score click rounds by duel wins ([de.yuuto.aimtune.core.score.RoundScorer]). */
    val duel: DuelRules? = null,
    val verified: Boolean = false,
) {
    init {
        require(movement == null || body != null) { "a movement block needs a body block for the top speeds" }
        require(strafe == null || movement != null) { "a strafe block needs a body and a movement block" }
        require(clickTargetDistanceQuantiles == null || body != null) { "distance quantiles need a body block for the head size" }
        require(clickDistanceBounds.max < arena.wallDistanceMeters) { "click targets must stand in front of the wall (${arena.wallDistanceMeters} m)" }
        require(fieldYawDegrees in 5.0..90.0 && fieldPitchDegrees in 3.0..60.0) { "field must be 5-90 x 3-60 degrees" }
        require(flickAngles.isNotEmpty() && flickAngles.sumOf { it.weight } > 0.0) { "flick angles need a positive weight" }
        require(flickAngles.zipWithNext().all { (a, b) -> a.maxDegrees < b.maxDegrees }) { "flick angle buckets must be increasing" }
        require(switchingSeparationDegrees > 0.0 && trackingDistanceMeters > 0.0) { "separation and distance must be positive" }
        require(stopProbability in 0.0..0.9) { "stop probability must be in 0..0.9" }
        require(eyeHeightMeters in 0.5..3.0 && headHeightMeters in 0.5..3.0) { "eye and head height must be in 0.5..3 m" }
        require(clickTargetDiameterDegrees.max < 30.0 && trackingDiameterDegrees.max < 30.0) { "target diameters over 30 degrees are not targets" }
    }

    /** True when click targets are heads drawn from measured distances instead of angular sizes. */
    val headsFromDistance: Boolean get() = clickTargetDistanceQuantiles != null && body != null

    /** The distances a click target may stand at; placement keeps moved targets inside them. */
    val clickDistanceBounds: PositiveRange get() = clickTargetDistanceQuantiles?.range ?: clickTargetDistanceMeters

    /** The distance of a new click target. */
    fun drawClickDistance(random: Random): Double = clickTargetDistanceQuantiles?.draw(random) ?: clickTargetDistanceMeters.uniform(random)

    /** World radius of a click target at [distance]: a head of the game's player, or a drawn angular size at that distance. */
    fun clickTargetRadius(random: Random, distance: Double): Double =
        if (headsFromDistance) checkNotNull(body).headDiameterMeters / 2.0 else radiusFor(clickTargetDiameterDegrees.logUniform(random), distance)

    /** How a player of the game moves on the ground; null without a `body` and a `movement` block. */
    val locomotion: Locomotion? get() = if (body != null && movement != null) Locomotion(body, movement) else null

    /** Pitch in degrees at which the head of an enemy standing on the player's level at [distance] meters appears (negative: below the crosshair). */
    fun levelPitchDegrees(distance: Double): Double = Math.toDegrees(atan((headHeightMeters - eyeHeightMeters) / distance))

    /** A pitch for a new target at [distance] meters, from the vertical distribution. */
    fun drawPitch(random: Random, distance: Double): Double = elevation.draw(random, levelPitchDegrees(distance), fieldPitchDegrees)

    /** World radius in meters of a sphere that looks [diameterDegrees] wide from [distance] meters. */
    fun radiusFor(diameterDegrees: Double, distance: Double): Double = distance * sin(Math.toRadians(diameterDegrees / 2.0))

    /**
     * Angular speed range in degrees per second of the tracked target at the tracking distance: from walking to running
     * speed when the game has a movement model, else from [strafeSpeedMetersPerSecond].
     */
    val trackingSpeedDegreesPerSecond: PositiveRange
        get() {
            val speeds = locomotion?.let { PositiveRange(it.topSpeed(Gait.WALK), it.topSpeed(Gait.RUN)) } ?: strafeSpeedMetersPerSecond
            return PositiveRange(Math.toDegrees(speeds.min / trackingDistanceMeters), Math.toDegrees(speeds.max / trackingDistanceMeters))
        }

    /** Draws an angle from the flick mix: a bucket by weight, then uniformly inside it. */
    fun drawFlickAngle(random: Random): Double {
        val total = flickAngles.sumOf { it.weight }
        var pick = random.nextDouble(total)
        var low = 0.0
        for (bucket in flickAngles) {
            if (pick < bucket.weight) return withinScreen(random.nextDouble(maxOf(low, MIN_FLICK_DEGREES), maxOf(bucket.maxDegrees, MIN_FLICK_DEGREES + 0.01)), random)
            pick -= bucket.weight
            low = bucket.maxDegrees
        }
        return withinScreen(flickAngles.last().maxDegrees, random)
    }

    /**
     * The next target has to be on the screen when the crosshair is on the previous one, otherwise the player cannot
     * find it: angles beyond what fits next to the middle of the picture are redrawn between 70 % and 100 % of that.
     */
    private fun withinScreen(angle: Double, random: Random): Double {
        val limit = flash.halfFovDegrees - SCREEN_EDGE_MARGIN_DEGREES
        return if (angle <= limit) angle else random.nextDouble(limit * 0.7, limit)
    }

    companion object {
        const val MIN_FLICK_DEGREES = 1.0
        private const val SCREEN_EDGE_MARGIN_DEGREES = 6.0
        val DEFAULT_FLICK_ANGLES = listOf(AngleBucket(5.0, 0.30), AngleBucket(10.0, 0.30), AngleBucket(20.0, 0.20), AngleBucket(40.0, 0.15), AngleBucket(90.0, 0.05))
        val DEFAULT = TrainerGeometry()
    }
}
