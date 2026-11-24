package dev.veqzo.aimtune.core.accel

import kotlin.math.nextUp

/**
 * The refine stages of a Raw Accel lookup table, in the table's own representation. What the y values mean depends on the
 * Gain / Velocity flag (`common/accel-lookup.hpp`, v1.7.1): with the flag set (`velocity`, `:49`, `:53`) y is the output speed and
 * the multiplier is `y / x` (`:78`, `:88`, `:95`); without it y is the multiplier itself. Below the first point the table is held at
 * the first point's multiplier (`:94-96`), so the multiplier at low speed is `y0` (velocity form) or `y0 / x0` (gain form), and
 * between points the table interpolates y linearly (`:83-90`).
 *
 * The first point is never touched, so the multiplier at low speed stays bit-identical.
 * - HIGH_GAIN scales the rise of the multiplier above its value at the first point: `m' = m0 + (m - m0) * s`, stored as `y' = m'`
 *   (flag off) or `y' = x * m'` (flag on), floored at [MIN_HIGH_OUTPUT].
 * - TRANSITION stretches the speed axis by t from the first point: `x' = x0 + (x - x0) * t`, with the same multiplier at the
 *   stretched speed (flag off: y unchanged; flag on: `y' = x' * m`).
 */
internal object RefineLut {
    private class Table(val xs: List<Double>, val ys: List<Double>, val gain: Boolean) {
        fun multiplier(i: Int) = if (gain) ys[i] / xs[i] else ys[i]
    }

    private fun table(args: RawAccelArgs) = Table(
        args.lut.filterIndexed { i, _ -> i % 2 == 0 }.map { it.toDouble() },
        args.lut.filterIndexed { i, _ -> i % 2 == 1 }.map { it.toDouble() },
        args.gain,
    )

    /** True when the table has a rise above its first multiplier to scale or stretch. */
    fun rises(args: RawAccelArgs): Boolean {
        val t = table(args)
        if (t.xs.size < 2 || (t.gain && t.xs.any { it <= 0.0 })) return false
        val m0 = t.multiplier(0)
        return (1 until t.xs.size).any { t.multiplier(it) != m0 }
    }

    fun highGain(args: RawAccelArgs, s: Double): RawAccelArgs {
        val t = table(args)
        val m0 = t.multiplier(0)
        val out = args.lut.toMutableList()
        for (i in 1 until t.xs.size) {
            val m = t.multiplier(i)
            val scaled = (m0 + (m - m0) * s).coerceAtLeast(minOf(m, MIN_HIGH_OUTPUT))
            out[2 * i + 1] = (if (t.gain) t.xs[i] * scaled else scaled).toFloat()
        }
        return args.copy(lut = out)
    }

    fun transition(args: RawAccelArgs, t: Double): RawAccelArgs {
        val table = table(args)
        val x0 = table.xs[0]
        val out = args.lut.toMutableList()
        for (i in 1 until table.xs.size) {
            // Raw Accel's binary search needs ascending x (accel-lookup.hpp:59-81); float rounding must not merge two points.
            val x = (x0 + (table.xs[i] - x0) * t).toFloat().coerceAtLeast(out[2 * (i - 1)].nextUp())
            out[2 * i] = x
            if (table.gain) out[2 * i + 1] = (x * table.multiplier(i)).toFloat()
        }
        return args.copy(lut = out)
    }
}
