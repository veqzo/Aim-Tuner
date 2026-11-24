package dev.veqzo.aimtune.core.accel

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * A sensitivity multiplier that depends on the input speed, with the formulas of Raw Accel 1.7.1
 * ("Gain / Velocity" set to Velocity, which Raw Accel's code calls LEGACY): the value is the factor
 * the driver applies to the mouse counts of one report. The speed is in inches per second after the
 * DPI normalization of the driver, i.e. counts per ms times 1000 / DPI. The formulas were read from
 * `common/accel-classic.hpp`, `accel-natural.hpp` and `accel-power.hpp` and their validation rules
 * from `common/rawaccel-validate.hpp`; see docs/ALGORITHMS.md ("Acceleration").
 */
sealed interface AccelCurve : AccelModel {
    /** Sensitivity multiplier at [speed] inches per second; 1 means unchanged. */
    fun multiplier(speed: Double): Double

    override fun newModifier(dpi: Double): MouseModifier = CurveModifier(this, dpi)

    override val finderCurve: AccelCurve get() = this

    override fun planningGain(speed: Double, dpi: Double): Double = multiplier(speed)
}

/** No acceleration: every speed gets the multiplier 1. */
data object NoAccel : AccelCurve {
    override fun multiplier(speed: Double) = 1.0
}

/**
 * Raw Accel "Classic": `1 + (a^(e-1) * (v - offset)^e / v)` above the input offset, limited by an
 * output cap. A [cap] above 1 is the largest multiplier; a [cap] below 1 turns the curve around and
 * lets it fall from 1 toward the cap; 0 means no cap (Raw Accel's cap mode "output").
 */
data class ClassicCurve(
    val acceleration: Double,
    val exponent: Double = 2.0,
    val inputOffset: Double = 0.0,
    val cap: Double = 0.0,
) : AccelCurve {
    init {
        require(acceleration > 0.0 && acceleration.isFinite()) { "acceleration must be positive" }
        require(exponent > 1.0 && exponent.isFinite()) { "exponent must be greater than 1" }
        require(inputOffset >= 0.0 && cap >= 0.0 && cap.isFinite()) { "offset and cap must not be negative" }
    }

    private val accelRaised = acceleration.pow(exponent - 1.0)
    private val limit = if (cap > 0.0) abs(cap - 1.0) else Double.MAX_VALUE
    private val sign = if (cap in 0.0..1.0 && cap > 0.0) -1.0 else 1.0

    override fun multiplier(speed: Double): Double {
        if (speed <= inputOffset) return 1.0
        return sign * min(accelRaised * (speed - inputOffset).pow(exponent) / speed, limit) + 1.0
    }
}

/**
 * Raw Accel "Natural": rises (or falls) from 1 toward [limit] with a decay set by [decayRate], so
 * the curve levels off by itself. A [limit] below 1 makes the sensitivity fall with speed.
 */
data class NaturalCurve(
    val decayRate: Double,
    val limit: Double,
    val inputOffset: Double = 0.0,
) : AccelCurve {
    init {
        require(decayRate > 0.0 && decayRate.isFinite()) { "decay rate must be positive" }
        require(limit > 0.0 && limit.isFinite() && limit != 1.0) { "limit must be positive and not 1" }
        require(inputOffset >= 0.0) { "offset must not be negative" }
    }

    private val range = limit - 1.0
    private val rate = decayRate / abs(range)

    override fun multiplier(speed: Double): Double {
        if (speed <= inputOffset) return 1.0
        val offsetSpeed = inputOffset - speed
        val decay = exp(rate * offsetSpeed)
        return range * (1.0 - (inputOffset - decay * offsetSpeed) / speed) + 1.0
    }
}

/**
 * Raw Accel "Look Up Table" with "Gain / Velocity" set to Velocity (`common/accel-lookup.hpp`, v1.7.1): the multiplier
 * is interpolated linearly in speed and multiplier between the points, and held at the first point's value below it and at
 * the last point's value above it, so a table built over the measured speeds stays flat outside them. Raw Accel keeps the
 * points as floats, so they are rounded to float here and the curve under test is the curve exported. At most
 * [MAX_POINTS] points with strictly increasing positive speeds and positive multipliers (the rules of its editor).
 */
class LookupCurve(speeds: List<Double>, multipliers: List<Double>) : AccelCurve {
    val speeds: List<Double> = speeds.map { it.toFloat().toDouble() }
    val multipliers: List<Double> = multipliers.map { it.toFloat().toDouble() }

    init {
        require(this.speeds.size == this.multipliers.size) { "one multiplier per speed expected" }
        require(this.speeds.size in 2..MAX_POINTS) { "a lookup table needs 2 to $MAX_POINTS points" }
        require(this.speeds.first() > 0.0 && this.speeds.all { it.isFinite() } && this.speeds.zipWithNext().all { (a, b) -> b > a }) {
            "speeds must be positive, finite and strictly increasing"
        }
        require(this.multipliers.all { it > 0.0 && it.isFinite() }) { "multipliers must be positive" }
    }

    override fun multiplier(speed: Double): Double {
        if (speed.isNaN() || speed <= speeds.first()) return multipliers.first()
        if (speed >= speeds.last()) return multipliers.last()
        val i = speeds.binarySearch(speed).let { if (it >= 0) return multipliers[it] else -it - 2 }
        val t = (speed - speeds[i]) / (speeds[i + 1] - speeds[i])
        return multipliers[i] + (multipliers[i + 1] - multipliers[i]) * t
    }

    override fun equals(other: Any?): Boolean = other is LookupCurve && speeds == other.speeds && multipliers == other.multipliers
    override fun hashCode(): Int = 31 * speeds.hashCode() + multipliers.hashCode()
    override fun toString(): String = "LookupCurve(${speeds.size} points, ${speeds.first()}-${speeds.last()} in/s)"

    companion object {
        /** `LUT_POINTS_CAPACITY` of Raw Accel 1.7.1 (`common/rawaccel-base.hpp`). */
        const val MAX_POINTS = 257
    }
}

/**
 * Raw Accel "Power": `(scale * v)^exponent` above the speed where it reaches [outputOffset], which
 * is the multiplier below that speed. With an offset of 1 the curve starts at 1. A [cap] above 0 is
 * the largest multiplier.
 */
data class PowerCurve(
    val scale: Double,
    val exponent: Double,
    val outputOffset: Double = 1.0,
    val cap: Double = 0.0,
) : AccelCurve {
    init {
        require(scale > 0.0 && scale.isFinite()) { "scale must be positive" }
        require(exponent > 0.0 && exponent.isFinite()) { "exponent must be positive" }
        require(outputOffset >= 0.0 && cap >= 0.0) { "offset and cap must not be negative" }
        require(cap == 0.0 || cap >= outputOffset) { "cap must not be below the offset" }
    }

    private val offsetSpeed = (outputOffset / (exponent + 1.0)).pow(1.0 / exponent) / scale
    private val constant = offsetSpeed * outputOffset * exponent / (exponent + 1.0)
    private val limit = if (cap > 0.0) cap else Double.MAX_VALUE

    override fun multiplier(speed: Double): Double {
        val base = if (speed <= offsetSpeed) outputOffset else (scale * speed).pow(exponent) + constant / speed
        return min(base, limit)
    }
}
