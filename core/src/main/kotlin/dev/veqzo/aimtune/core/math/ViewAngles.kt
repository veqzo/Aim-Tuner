package dev.veqzo.aimtune.core.math

import kotlin.math.cos
import kotlin.math.sin

/**
 * Camera orientation in degrees. Yaw 0 looks along -z and grows to the right, pitch grows upward.
 * Instances are immutable; [rotatedByCounts] returns the new orientation.
 */
data class ViewAngles(val yawDegrees: Double = 0.0, val pitchDegrees: Double = 0.0) {

    /** Applies raw mouse counts: positive [dx] turns right, positive [dy] (mouse down) looks down. */
    fun rotatedByCounts(dx: Int, dy: Int, degreesPerCount: Double): ViewAngles = rotatedByCounts(dx.toDouble(), dy.toDouble(), degreesPerCount)

    /** Counts scaled by an acceleration curve are no longer whole numbers. */
    fun rotatedByCounts(dx: Double, dy: Double, degreesPerCount: Double): ViewAngles {
        val yaw = wrapDegrees(yawDegrees + dx * degreesPerCount)
        val pitch = (pitchDegrees - dy * degreesPerCount).coerceIn(-MAX_PITCH_DEGREES, MAX_PITCH_DEGREES)
        return ViewAngles(yaw, pitch)
    }

    fun direction(): Vec3 {
        val yaw = Math.toRadians(yawDegrees)
        val pitch = Math.toRadians(pitchDegrees)
        return Vec3(sin(yaw) * cos(pitch), sin(pitch), -cos(yaw) * cos(pitch))
    }

    companion object {
        const val MAX_PITCH_DEGREES = 89.0

        /** Direction for the given angles, as used to place targets. */
        fun directionOf(yawDegrees: Double, pitchDegrees: Double): Vec3 = ViewAngles(yawDegrees, pitchDegrees).direction()

        private fun wrapDegrees(angle: Double): Double {
            val wrapped = ((angle + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            return if (wrapped == -180.0) 180.0 else wrapped
        }
    }
}
