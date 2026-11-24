package dev.veqzo.aimtune.core.math

import kotlin.math.acos
import kotlin.math.sqrt

/** Immutable 3D vector. Convention: x right, y up, forward is -z (same as LibGDX). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    val length: Double get() = sqrt(this dot this)

    fun normalized(): Vec3 {
        val len = length
        require(len > 0.0) { "cannot normalize a zero vector" }
        return Vec3(x / len, y / len, z / len)
    }

    /** Angle to [other] in degrees, in [0, 180]. */
    fun angleToDegrees(other: Vec3): Double {
        val cos = (normalized() dot other.normalized()).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}
