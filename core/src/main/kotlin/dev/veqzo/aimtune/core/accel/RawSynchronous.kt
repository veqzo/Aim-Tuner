package dev.veqzo.aimtune.core.accel

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tanh

/**
 * The synchronous mode of Raw Accel 1.7.1, a port of `common/accel-synchronous.hpp` (`activation_framework`). Legacy is the closed
 * form; Gain is the integral of the legacy curve, tabulated once into floats on `fp_rep_range{-3, 9, 8}` (accel-synchronous.hpp:85)
 * and read back with the interpolation of `:108-131`, like the driver does.
 */
internal object RawSynchronous {
    /** `fp_rep_range`, accel-lookup.hpp:10-33 */
    private const val RANGE_START = -3
    private const val RANGE_STOP = 9
    private const val RANGE_NUM = 8
    private const val RANGE_SIZE = (RANGE_STOP - RANGE_START) * RANGE_NUM + 1

    /** `LUT_RAW_DATA_CAPACITY`, the `capacity` of accel-synchronous.hpp:77 */
    private const val CAPACITY = RawAccelArgs.RAW_DATA_CAPACITY

    /** `activation_framework<LEGACY>`, accel-synchronous.hpp:9-73 */
    fun legacy(args: RawAccelArgs): (Double) -> Double {
        val logMotivity = ln(args.motivity) // :22
        val gammaConst = args.gamma / logMotivity // :23
        val logSyncspeed = ln(args.syncSpeed) // :24
        val syncspeed = args.syncSpeed
        val sharpness = if (args.smooth == 0.0) 16.0 else 0.5 / args.smooth // :26
        val sharpnessRecip = 1 / sharpness // :27
        val useLinearClamp = sharpness >= 16 // :28
        val minimumSens = 1 / args.motivity // :29
        val maximumSens = args.motivity // :30
        return fun(x: Double): Double {
            if (useLinearClamp) { // :36-51
                val logSpace = gammaConst * (ln(x) - logSyncspeed)
                if (logSpace < -1) return minimumSens
                if (logSpace > 1) return maximumSens
                return exp(logSpace * logMotivity)
            }
            if (x == syncspeed) return 1.0 // :53-55
            val logDiff = ln(x) - logSyncspeed // :57-58
            if (logDiff > 0) { // :60-65
                val logSpace = gammaConst * logDiff
                val exponent = tanh(logSpace.pow(sharpness)).pow(sharpnessRecip)
                return exp(exponent * logMotivity)
            }
            val logSpace = -gammaConst * logDiff // :68-70
            val exponent = -tanh(logSpace.pow(sharpness)).pow(sharpnessRecip)
            return exp(exponent * logMotivity)
        }
    }

    /** `activation_framework<GAIN>`, accel-synchronous.hpp:75-148. The table holds the output speeds (`velocity` is true, `:85`). */
    fun gain(args: RawAccelArgs): (Double) -> Double {
        val data = buildTable(legacy(args))
        val xStart = rawScalbn(1.0, RANGE_START) // :137
        return fun(x: Double): Double {
            val e = minOf(rawIlogb(x), RANGE_STOP - 1) // :112
            if (e >= RANGE_START) { // :114
                val idxIntLogPart = e - RANGE_START
                val idxFracLinPart = rawScalbn(x, -e) - 1
                val idxF = RANGE_NUM * (idxIntLogPart + idxFracLinPart)
                val idx = minOf(idxF.toInt(), RANGE_SIZE - 2) // :119, static_cast<int> truncates
                if (idx < CAPACITY - 1) { // :121
                    val y = rawLerp(data[idx].toDouble(), data[idx + 1].toDouble(), idxF - idx)
                    return y / x // :123
                }
            }
            return data[0].toDouble() / xStart // :128-129
        }
    }

    /** accel-synchronous.hpp:87-106 and :140-146 with `fp_rep_range::for_each` (accel-lookup.hpp:15-27). */
    private fun buildTable(sig: (Double) -> Double): FloatArray {
        val table = FloatArray(RANGE_SIZE)
        var sum = 0.0
        var a = 0.0
        var i = 0
        fun sigmoidSum(b: Double): Double {
            val partitions = 2
            val interval = (b - a) / partitions
            for (k in 1..partitions) sum += sig(a + k * interval) * interval
            a = b
            return sum
        }
        for (e in 0 until RANGE_STOP - RANGE_START) {
            val expScale = rawScalbn(1.0, e + RANGE_START) / RANGE_NUM
            for (j in 0 until RANGE_NUM) table[i++] = sigmoidSum((j + RANGE_NUM) * expScale).toFloat()
        }
        table[i] = sigmoidSum(rawScalbn(1.0, RANGE_STOP)).toFloat()
        return table
    }
}
