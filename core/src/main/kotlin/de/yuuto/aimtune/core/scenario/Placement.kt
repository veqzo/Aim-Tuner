package de.yuuto.aimtune.core.scenario

import de.yuuto.aimtune.core.math.Vec3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Yaw and pitch in degrees of the direction to [center]. */
internal fun anglesOf(center: Vec3): Pair<Double, Double> =
    Math.toDegrees(atan2(center.x, -center.z)) to Math.toDegrees(asin(center.y / center.length))

/**
 * A spot [angle] degrees from ([yaw], [pitch]) in a random direction that lies inside the field. The
 * angle is measured on the screen plane (yaw scaled by cos(pitch)), which for these small fields is
 * the true angle within a few percent. Falls back to stepping toward the middle when the random
 * directions all leave the field (the previous target sits in a corner).
 */
internal fun placeAt(yaw: Double, pitch: Double, angle: Double, random: Random, fieldYaw: Double, fieldPitch: Double): Pair<Double, Double> {
    repeat(MAX_PLACEMENT_TRIES) {
        val heading = random.nextDouble(0.0, 2 * Math.PI)
        val nextPitch = pitch + angle * sin(heading)
        val nextYaw = yaw + angle * cos(heading) / cos(Math.toRadians(nextPitch))
        if (abs(nextYaw) <= fieldYaw && abs(nextPitch) <= fieldPitch) return nextYaw to nextPitch
    }
    val towardYaw = if (yaw > 0) -1.0 else 1.0
    return (yaw + towardYaw * minOf(angle, abs(yaw) + fieldYaw / 2)).coerceIn(-fieldYaw, fieldYaw) to pitch
}

/**
 * A spot [angle] degrees from ([yaw], [pitch]) for a target at [distance] meters, with its height taken from the
 * geometry's vertical distribution: most targets stay near head level, so a flick is mostly horizontal. The pitch
 * is drawn first and limited to 90 % of [angle]; the rest of the angle goes into yaw, to the side that stays in
 * the field. With the old uniform field this is the isotropic [placeAt].
 */
internal fun placeNearLevel(
    g: TrainerGeometry,
    yaw: Double,
    pitch: Double,
    angle: Double,
    distance: Double,
    random: Random,
    fieldYaw: Double,
    fieldPitch: Double,
): Pair<Double, Double> {
    if (g.elevation.uniform) return placeAt(yaw, pitch, angle, random, fieldYaw, fieldPitch)
    val wanted = g.elevation.draw(random, g.levelPitchDegrees(distance), fieldPitch)
    val step = (wanted - pitch).coerceIn(-VERTICAL_LIMIT * angle, VERTICAL_LIMIT * angle)
    val nextPitch = pitch + step
    val sideways = sqrt(angle * angle - step * step) / cos(Math.toRadians(nextPitch))
    val sign = if (random.nextBoolean()) 1.0 else -1.0
    for (candidate in listOf(yaw + sign * sideways, yaw - sign * sideways)) {
        if (abs(candidate) <= fieldYaw) return candidate to nextPitch
    }
    val toward = if (yaw > 0) -1.0 else 1.0
    return (yaw + toward * minOf(sideways, abs(yaw) + fieldYaw / 2)).coerceIn(-fieldYaw, fieldYaw) to nextPitch
}

/** Where a target stands: direction in degrees and distance in meters. */
internal data class Spot(val yaw: Double, val pitch: Double, val distance: Double)

/**
 * For [TrainerGeometry.arena]'s share of targets, moves the spot to the corner or doorway nearest in yaw (within the
 * layout's snap angle): the target then stands at the feature's yaw and distance, keeping its height relative to head level.
 */
internal fun snapToFeature(g: TrainerGeometry, random: Random, spot: Spot): Spot {
    val arena = g.arena
    if (arena.featureShare <= 0.0 || g.elevation.uniform || random.nextDouble() >= arena.featureShare) return spot
    val feature = arena.featureNear(spot.yaw) ?: return spot
    val distance = feature.distanceMeters.coerceIn(g.clickDistanceBounds.min, g.clickDistanceBounds.max)
    val pitch = spot.pitch - g.levelPitchDegrees(spot.distance) + g.levelPitchDegrees(distance)
    return Spot(feature.yawDegrees, pitch.coerceIn(-g.fieldPitchDegrees, g.fieldPitchDegrees), distance)
}

/**
 * Moves a target that stands behind a pillar or a door post of the map so that it can be seen from the player's
 * position: closer than the blocker when the distance range allows it, otherwise to the side. The old uniform field has
 * no map and is left alone.
 */
internal fun ensureVisible(g: TrainerGeometry, random: Random, spot: Spot, marginDegrees: Double = VISIBLE_MARGIN_DEGREES): Spot {
    if (g.elevation.uniform) return spot
    var current = spot
    repeat(MAX_VISIBILITY_TRIES) {
        val blocker = g.arena.occluderFor(current.yaw, current.distance, marginDegrees) ?: return current
        val nearest = blocker.distance - VISIBLE_GAP_METERS
        current = if (nearest >= g.clickDistanceBounds.min) {
            val distance = random.nextDouble(g.clickDistanceBounds.min, nearest)
            current.copy(pitch = current.pitch - g.levelPitchDegrees(current.distance) + g.levelPitchDegrees(distance), distance = distance)
        } else {
            val toward = if (current.yaw >= (blocker.yawLow + blocker.yawHigh) / 2) 1.0 else -1.0
            current.copy(yaw = (if (toward > 0) blocker.yawHigh + marginDegrees + 0.5 else blocker.yawLow - marginDegrees - 0.5))
        }
    }
    return current
}

private const val MAX_VISIBILITY_TRIES = 6
private const val VISIBLE_GAP_METERS = 1.0
private const val VISIBLE_MARGIN_DEGREES = 1.0
private const val VERTICAL_LIMIT = 0.9
private const val MAX_PLACEMENT_TRIES = 60
