package dev.veqzo.aimtune.core.accel

/** `common/utility.hpp:5` (`minsd`): [a] if it is smaller than [b], else [b] (NaN in [a] gives [b]). */
internal fun minsd(a: Double, b: Double): Double = if (a < b) a else b

/** `common/utility.hpp:10` (`maxsd`). */
internal fun maxsd(a: Double, b: Double): Double = if (b < a) a else b

/** `common/utility.hpp:15` (`clampsd`). */
internal fun clampsd(v: Double, lo: Double, hi: Double): Double = minsd(maxsd(v, lo), hi)

/** `common/accel-lookup.hpp:35-43`. Does not clamp towards t > 1, it extrapolates. */
internal fun rawLerp(a: Double, b: Double, t: Double): Double {
    val x = a + t * (b - a)
    return if ((t > 1) == (a < b)) maxsd(x, b) else minsd(x, b)
}

/** `common/utility.hpp:39-43` (`ilogb`): the exponent bits of [x], also for zero, subnormal, negative, infinite and NaN values. */
internal fun rawIlogb(x: Double): Int = ((java.lang.Double.doubleToRawLongBits(x) ushr 52) and 0x7ff).toInt() - 0x3ff

/** `common/utility.hpp:46-51` (`scalbn`): [x] times 2^[n] for n in [-1022, 1023], built from the exponent bits like the C++. */
internal fun rawScalbn(x: Double, n: Int): Double = x * java.lang.Double.longBitsToDouble((0x3ff + n).toLong() shl 52)

/** `min` of `common/utility.hpp:21`: `(b < a) ? b : a`; unlike `minOf` it does not propagate NaN or order -0.0. */
internal fun cppMin(a: Double, b: Double): Double = if (b < a) b else a
