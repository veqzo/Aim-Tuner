package dev.veqzo.aimtune.core.units

import kotlin.math.atan
import kotlin.math.tan

enum class FovType { HORIZONTAL, VERTICAL }

/**
 * Field of view as a game defines it. [verticalDegrees] converts it for a renderer that takes a
 * vertical FOV, using the aspect ratio the game's FOV value refers to.
 */
data class Fov(val degrees: Double, val type: FovType) {
    init {
        require(degrees > 0.0 && degrees < 180.0) { "fov must be in (0, 180) degrees, was $degrees" }
    }

    fun verticalDegrees(aspectRatio: Double): Double {
        require(aspectRatio.isFinite() && aspectRatio > 0.0) { "aspectRatio must be positive, was $aspectRatio" }
        return when (type) {
            FovType.VERTICAL -> degrees
            FovType.HORIZONTAL -> Math.toDegrees(2.0 * atan(tan(Math.toRadians(degrees) / 2.0) / aspectRatio))
        }
    }
}
