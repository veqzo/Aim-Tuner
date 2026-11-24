package dev.veqzo.aimtune.core.accel

import kotlin.math.pow

/** The power mode of Raw Accel 1.7.1, a port of `common/accel-power.hpp`. */
internal object RawPower {
    private const val DBL_MAX = Double.MAX_VALUE

    /** `power_base`, accel-power.hpp:9-71 (the constructor, `base_fn` and the four static helpers). */
    private class Base(private val args: RawAccelArgs) {
        val offsetX: Double
        val offsetY: Double
        val scale: Double
        private val constant: Double

        init {
            val n = args.exponentPower
            val legacyIo = args.capMode == RawAccelCapMode.IN_OUT && !args.gain
            scale = when {
                args.capMode != RawAccelCapMode.IN_OUT -> args.scale // :19
                args.gain -> scaleFromGainPoint(args.capX, args.capY, n) // :22
                else -> scaleFromOutputPoint(args.capX, args.capY, n, 0.0) // :25-33, constant is 0
            }
            if (legacyIo) {
                // :31-34 the offset is ignored (circular dependency scale -> constant -> offset)
                offsetX = 0.0
                offsetY = 0.0
                constant = 0.0
            } else {
                offsetX = gainInverse(args.outputOffset, n, scale) // :37
                offsetY = args.outputOffset // :38
                constant = offsetX * offsetY * n / (n + 1) // :39
            }
        }

        /** accel-power.hpp:42-50 */
        fun baseFn(x: Double): Double =
            if (x <= offsetX) offsetY else (scale * x).pow(args.exponentPower) + constant / x
    }

    // accel-power.hpp:52-69
    private fun gainOf(input: Double, power: Double, scale: Double) = (power + 1) * (input * scale).pow(power)
    private fun gainInverse(gain: Double, power: Double, scale: Double) = (gain / (power + 1)).pow(1 / power) / scale
    private fun scaleFromGainPoint(input: Double, gain: Double, power: Double) = (gain / (power + 1)).pow(1 / power) / input
    private fun scaleFromOutputPoint(input: Double, output: Double, power: Double, c: Double) = (output - c / input).pow(1 / power) / input

    /** `power<LEGACY>`, accel-power.hpp:75-102 */
    fun legacy(args: RawAccelArgs): (Double) -> Double {
        val b = Base(args)
        var cap = DBL_MAX
        when (args.capMode) {
            RawAccelCapMode.IN_OUT -> cap = args.capY
            RawAccelCapMode.INPUT -> if (args.capX > 0) cap = b.baseFn(args.capX)
            RawAccelCapMode.OUTPUT -> if (args.capY > 0) cap = args.capY
        }
        return { speed -> minsd(b.baseFn(speed), cap) }
    }

    /** `power<GAIN>`, accel-power.hpp:104-162 */
    fun gain(args: RawAccelArgs): (Double) -> Double {
        val b = Base(args)
        var capX = DBL_MAX
        var capY = DBL_MAX
        var constantB = 0.0
        var done = false
        when (args.capMode) {
            RawAccelCapMode.IN_OUT -> {
                capX = args.capX
                capY = args.capY
            }
            RawAccelCapMode.INPUT -> if (args.capX > 0) {
                if (args.capX <= b.offsetX) {
                    // :119-123
                    capX = 0.0
                    capY = b.offsetY
                    constantB = 0.0
                    done = true
                } else {
                    capX = args.capX
                    capY = gainOf(args.capX, args.exponentPower, b.scale)
                }
            }
            RawAccelCapMode.OUTPUT -> if (args.capY > 0) {
                capX = gainInverse(args.capY, args.exponentPower, b.scale)
                capY = args.capY
            }
        }
        if (!done) constantB = (b.baseFn(capX) - capY) * capX // :145 and :158-161
        return { speed -> if (speed < capX) b.baseFn(speed) else capY + constantB / speed }
    }
}
