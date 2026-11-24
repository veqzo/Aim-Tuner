package dev.veqzo.aimtune.core.scenario

import dev.veqzo.aimtune.core.math.Vec3
import kotlin.math.sqrt

/**
 * The hit model of a range bot, an original humanoid: a head sphere on a body capsule (legs and torso). The scenario hits
 * the head sphere [Target] and the capsule; the renderer draws the same proportions with boxes, so what is drawn is what is
 * hit. Everything is measured from the head centre, so a bot is fully described by where its head is.
 */
data class BotShape(val heightMeters: Double, val headDiameterMeters: Double) {
    val headRadius: Double get() = headDiameterMeters / 2.0

    /** From the head centre down to the soles of the feet. */
    val headCenterAboveFeet: Double get() = heightMeters - headRadius

    /** The capsule's axis runs from [BODY_BOTTOM_ABOVE_FEET] above the soles to a neck gap below the head. */
    val axisTopBelowHead: Double get() = headRadius + NECK_GAP + BODY_RADIUS
    val axisBottomBelowHead: Double get() = headCenterAboveFeet - BODY_BOTTOM_ABOVE_FEET

    /** True if the ray hits the body (not the head) of a bot whose head centre is [head]. */
    fun bodyHitBy(head: Vec3, origin: Vec3, direction: Vec3): Boolean {
        val top = Vec3(head.x, head.y - axisTopBelowHead, head.z)
        val bottom = Vec3(head.x, head.y - axisBottomBelowHead, head.z)
        return distanceRayToSegment(origin, direction, bottom, top) <= BODY_RADIUS
    }

    companion object {
        const val BODY_RADIUS = 0.2
        const val NECK_GAP = 0.05
        const val BODY_BOTTOM_ABOVE_FEET = BODY_RADIUS
        private const val RAY_LENGTH = 200.0

        /** Smallest distance between the ray from [origin] along the unit [direction] and the segment [a]..[b]. */
        internal fun distanceRayToSegment(origin: Vec3, direction: Vec3, a: Vec3, b: Vec3): Double {
            val d1 = direction * RAY_LENGTH
            val d2 = b - a
            val r = origin - a
            val aa = d1 dot d1
            val ee = d2 dot d2
            val f = d2 dot r
            val c = d1 dot r
            val bb = d1 dot d2
            val denominator = aa * ee - bb * bb
            var s = if (denominator > EPSILON) ((bb * f - c * ee) / denominator).coerceIn(0.0, 1.0) else 0.0
            var t = (bb * s + f) / ee
            if (t < 0.0) {
                t = 0.0
                s = (-c / aa).coerceIn(0.0, 1.0)
            } else if (t > 1.0) {
                t = 1.0
                s = ((bb - c) / aa).coerceIn(0.0, 1.0)
            }
            val onRay = origin + d1 * s
            val onSegment = a + d2 * t
            val gap = onRay - onSegment
            return sqrt(gap dot gap)
        }

        private const val EPSILON = 1e-12
    }
}
