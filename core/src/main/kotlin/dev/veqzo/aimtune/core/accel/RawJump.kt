package dev.veqzo.aimtune.core.accel

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln

/** The jump mode of Raw Accel 1.7.1, a port of `common/accel-jump.hpp`. The step is `cap.x` (position) and `cap.y - 1` (height). */
internal object RawJump {
    private class Base(args: RawAccelArgs) {
        val stepX = args.capX // accel-jump.hpp:15
        val stepY = args.capY - 1
        private val rateInverse = args.smooth * stepX // :17
        private val smoothRate = if (rateInverse < 1) 0.0 else SMOOTH_SCALE / rateInverse // :19-24
        val isSmooth get() = smoothRate != 0.0 // :27-30

        private fun decay(x: Double) = exp(smoothRate * (stepX - x)) // :32-35
        fun smooth(x: Double) = stepY / (1 + decay(x)) // :37-40
        fun smoothAntideriv(x: Double) = stepY * (x + ln(1 + decay(x)) / smoothRate) // :42-45
    }

    /** accel-jump.hpp:8 */
    private const val SMOOTH_SCALE = 2 * PI

    /** `jump<LEGACY>`, accel-jump.hpp:51-61 */
    fun legacy(args: RawAccelArgs): (Double) -> Double {
        val b = Base(args)
        return { x -> if (b.isSmooth) b.smooth(x) + 1 else if (x < b.stepX) 1.0 else 1 + b.stepY }
    }

    /** `jump<GAIN>`, accel-jump.hpp:63-81 */
    fun gain(args: RawAccelArgs): (Double) -> Double {
        val b = Base(args)
        val c = -b.smoothAntideriv(0.0) // :69
        return { x ->
            when {
                x <= 0 -> 1.0
                b.isSmooth -> 1 + (b.smoothAntideriv(x) + c) / x
                x < b.stepX -> 1.0
                else -> 1 + b.stepY * (x - b.stepX) / x
            }
        }
    }
}
