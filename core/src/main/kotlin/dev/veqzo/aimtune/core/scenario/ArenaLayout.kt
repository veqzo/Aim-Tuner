package dev.veqzo.aimtune.core.scenario

import kotlin.math.abs
import kotlin.math.atan2

enum class ArenaFeatureKind { CORNER, DOORWAY }

/**
 * A place where an enemy can stand: next to a wall corner or in a doorway, at [yawDegrees] and
 * [distanceMeters] from the player. The scene draws the corner or door frame there; the scenarios
 * sometimes put a target at the feature instead of at the drawn spot.
 */
data class ArenaFeature(val kind: ArenaFeatureKind, val yawDegrees: Double, val distanceMeters: Double) {
    init {
        require(yawDegrees in -180.0..180.0 && distanceMeters > 0.0) { "feature needs a yaw in -180..180 and a positive distance" }
    }
}

/**
 * The minimal map the trainer draws: a floor at eye height below the camera, a far wall and corner and
 * doorway features. It exists so targets stand where enemies stand (on the floor, at corners and doors)
 * and so the player can place the crosshair on head height. The scenarios stay angle-based: a feature
 * only moves a drawn target to the nearest feature when that is within [snapDegrees] of its yaw, which
 * happens for [featureShare] of the flick and switching targets, so the angle mix of the profile is
 * kept to within [snapDegrees].
 */
data class ArenaLayout(
    val wallDistanceMeters: Double = 36.0,
    val features: List<ArenaFeature> = DEFAULT_FEATURES,
    /** Share of flick and switching targets that stand at a feature when one is within [snapDegrees]. */
    val featureShare: Double = 0.3,
    val snapDegrees: Double = 2.5,
) {
    init {
        require(wallDistanceMeters in 20.0..80.0) { "wall distance must be 20..80 m" }
        require(featureShare in 0.0..1.0 && snapDegrees in 0.0..10.0) { "feature share must be 0..1 and snap 0..10 degrees" }
    }

    /** A part of the map that blocks the view: the yaw range it covers from the origin and its distance. */
    data class Occluder(val yawLow: Double, val yawHigh: Double, val distance: Double)

    /** The pillars of the corners and the door posts, as the scene draws them (same sizes, same side per feature). */
    fun occluders(): List<Occluder> = features.flatMapIndexed { index, f ->
        val spans = when (f.kind) {
            ArenaFeatureKind.CORNER -> {
                val side = if (index % 2 == 0) 1.0 else -1.0
                listOf(listOf(side * ENEMY_CLEARANCE, side * (ENEMY_CLEARANCE + CORNER_WIDTH)).sorted())
            }
            ArenaFeatureKind.DOORWAY -> listOf(
                listOf(-DOOR_HALF_WIDTH - JAMB / 2, -DOOR_HALF_WIDTH + JAMB / 2),
                listOf(DOOR_HALF_WIDTH - JAMB / 2, DOOR_HALF_WIDTH + JAMB / 2),
            )
        }
        spans.map { (low, high) ->
            Occluder(f.yawDegrees + Math.toDegrees(atan2(low, f.distanceMeters)), f.yawDegrees + Math.toDegrees(atan2(high, f.distanceMeters)), f.distanceMeters)
        }
    }

    /** A part of the map nearer than [distance] that hides a target at [yawDegrees] (widened by [marginDegrees] for the size of the target), if any. */
    fun occluderFor(yawDegrees: Double, distance: Double, marginDegrees: Double): Occluder? =
        occluders().firstOrNull { it.distance < distance - NEAR_MARGIN && yawDegrees in (it.yawLow - marginDegrees)..(it.yawHigh + marginDegrees) }

    /** The feature nearest in yaw to [yawDegrees], if it is within [snapDegrees]. */
    fun featureNear(yawDegrees: Double): ArenaFeature? =
        features.minByOrNull { abs(it.yawDegrees - yawDegrees) }?.takeIf { abs(it.yawDegrees - yawDegrees) <= snapDegrees }

    companion object {
        /** Corners and doorways every 20-30 degrees at distances typical for duels, within the field of the flick scenario. */
        val DEFAULT_FEATURES = listOf(
            ArenaFeature(ArenaFeatureKind.CORNER, -80.0, 9.0),
            ArenaFeature(ArenaFeatureKind.DOORWAY, -55.0, 22.0),
            ArenaFeature(ArenaFeatureKind.CORNER, -32.0, 14.0),
            ArenaFeature(ArenaFeatureKind.DOORWAY, -10.0, 28.0),
            ArenaFeature(ArenaFeatureKind.CORNER, 12.0, 11.0),
            ArenaFeature(ArenaFeatureKind.DOORWAY, 33.0, 18.0),
            ArenaFeature(ArenaFeatureKind.CORNER, 55.0, 24.0),
            ArenaFeature(ArenaFeatureKind.DOORWAY, 80.0, 12.0),
        )
        /** Sizes in meters shared with the scene: a corner pillar, the free space between an enemy and its pillar, a door's half width and post. */
        const val CORNER_WIDTH = 1.6
        const val ENEMY_CLEARANCE = 0.35
        const val DOOR_HALF_WIDTH = 1.1
        const val JAMB = 0.4
        private const val NEAR_MARGIN = 0.5
        val DEFAULT = ArenaLayout()
        val NONE = ArenaLayout(featureShare = 0.0)
    }
}
