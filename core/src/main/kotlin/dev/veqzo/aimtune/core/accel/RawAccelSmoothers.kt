package dev.veqzo.aimtune.core.accel

import kotlin.math.pow
import kotlin.math.sqrt

/** `simple_ema_smoother`, `common/rawaccel.hpp:62-91`. [time] is in report milliseconds. */
internal class SimpleEmaSmoother(halfLife: Double) {
    private val windowCoefficient = if (halfLife > 0) 0.5.pow(1 / halfLife) else 0.0 // :72
    private val cutoffCoefficient = 1.0 - sqrt(1.0 - windowCoefficient) // :73
    private var windowTotal = 0.0
    private var cutoffTotal = 0.0

    fun smooth(speed: Double, time: Double): Double {
        val timeAdjustedWindowCoefficient = 1 - windowCoefficient.pow(time) // :82
        val timeAdjustedCutoffCoefficient = 1 - cutoffCoefficient.pow(time) // :83
        windowTotal += timeAdjustedWindowCoefficient * (speed - windowTotal) // :86
        cutoffTotal += timeAdjustedCutoffCoefficient * (speed - cutoffTotal) // :87
        return cppMin(windowTotal, cutoffTotal) // :89, `min` of utility.hpp:21 is (b < a) ? b : a
    }
}

/** `linear_ema_smoother`, `common/rawaccel.hpp:96-163`. */
internal class LinearEmaSmoother(halfLife: Double, trendHalfLife: Double) {
    private val windowCoefficient = if (halfLife > 0) 0.5.pow(1 / halfLife) else 0.0 // :115
    private val windowTrendCoefficient = if (trendHalfLife > 0) 0.5.pow(1 / trendHalfLife) else 0.0 // :116
    private val cutoffCoefficient = 1.0 - sqrt(1.0 - windowCoefficient) // :117
    private val cutoffTrendCoefficient = 1.0 - sqrt(1.0 - windowTrendCoefficient) // :118
    private var windowTotal = 0.0
    private var cutoffTotal = 0.0
    private var windowTrendTotal = 0.0
    private var cutoffTrendTotal = 0.0

    fun smooth(speed: Double, time: Double): Double {
        val timeAdjustedWindowCoefficient = 1 - windowCoefficient.pow(time) // :129
        val timeAdjustedCutoffCoefficient = 1 - cutoffCoefficient.pow(time) // :130
        val timeAdjustedWindowTrendCoefficient = 1 - windowTrendCoefficient.pow(time) // :132
        val timeAdjustedCutoffTrendCoefficient = 1 - cutoffTrendCoefficient.pow(time) // :133

        val oldWindowTotal = windowTotal // :136
        val oldCutoffTotal = cutoffTotal // :137

        windowTrendTotal *= TREND_DAMPENING // :140
        cutoffTrendTotal *= TREND_DAMPENING // :141

        windowTotal += windowTrendTotal * time // :144
        cutoffTotal += cutoffTrendTotal * time // :145

        windowTotal += timeAdjustedWindowCoefficient * (speed - windowTotal) // :148
        cutoffTotal += timeAdjustedCutoffCoefficient * (speed - cutoffTotal) // :149

        windowTotal = maxsd(windowTotal, 0.0) // :152 `max(a, b)` is (b < a) ? a : b, the same as maxsd
        cutoffTotal = maxsd(cutoffTotal, 0.0) // :153

        val newWindowTrend = if (time > 0) (windowTotal - oldWindowTotal) / time else 0.0 // :156
        val newCutoffTrend = if (time > 0) (cutoffTotal - oldCutoffTotal) / time else 0.0 // :157
        windowTrendTotal += timeAdjustedWindowTrendCoefficient * (newWindowTrend - windowTrendTotal) // :158
        cutoffTrendTotal += timeAdjustedCutoffTrendCoefficient * (newCutoffTrend - cutoffTrendTotal) // :159

        return cppMin(windowTotal, cutoffTotal) // :161
    }

    private companion object {
        /** rawaccel.hpp:100 */
        const val TREND_DAMPENING = 0.75
    }
}

/** `smoother` (rawaccel.hpp:173-177): the three smoothers of one axis, each present only when its half-life is positive (`:212-232`). */
internal class AxisSmoothers(args: RawAccelSpeedArgs) {
    val input = if (args.inputHalfLife > 0) LinearEmaSmoother(args.inputHalfLife, INPUT_TREND_HALF_LIFE) else null
    val scale = if (args.scaleHalfLife > 0) SimpleEmaSmoother(args.scaleHalfLife) else null
    val output = if (args.outputHalfLife > 0) LinearEmaSmoother(args.outputHalfLife, OUTPUT_TREND_HALF_LIFE) else null

    companion object {
        /** rawaccel.hpp:190-191 */
        const val INPUT_TREND_HALF_LIFE = 1.25
        const val OUTPUT_TREND_HALF_LIFE = 0.7
    }
}
