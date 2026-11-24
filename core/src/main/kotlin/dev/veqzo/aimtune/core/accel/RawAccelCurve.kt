package dev.veqzo.aimtune.core.accel

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * The multiplier of a Raw Accel 1.7.1 acceleration mode at a speed in inches per second, computed with the code of the driver:
 * classic Velocity/Gain `common/accel-classic.hpp:27-77` / `:79-170`, natural `accel-natural.hpp:25-37` / `:40-57`, lut
 * `accel-lookup.hpp:55-97`, noaccel returns 1. The callback that wraps all of them multiplies `(f(x) - 1)` by the range weight
 * (`rawaccel.hpp:451`), which is 1 for the profiles AimTune accepts. The derived parameters (the classic `a` of a cap mode that
 * fixes the curve by a point, the Gain cap and its integration constant) are computed once in the constructor, in the order of
 * the C++ constructors.
 *
 * The speed axis is the one of the driver: counts per ms times 1000 / *device* DPI (see [RawAccelProfile]).
 *
 * Beyond the last lut point the table is extended linearly, not held: the driver interpolates between the last two points with
 * t > 1 and `lerp` (`accel-lookup.hpp:93-101`) does not clamp in that direction. This differs from [LookupCurve], which holds the
 * last value and stays as it is for stored rounds.
 */
data class RawAccelCurve(val args: RawAccelArgs) : AccelCurve {
    private val evaluate: (Double) -> Double = when (args.mode) {
        RawAccelMode.CLASSIC -> if (args.gain) classicGain(args) else classicVelocity(args)
        RawAccelMode.NATURAL -> if (args.gain) naturalGain(args) else naturalVelocity(args)
        RawAccelMode.LUT -> lookup(args)
        RawAccelMode.NOACCEL -> { _ -> 1.0 }
        RawAccelMode.JUMP -> if (args.gain) RawJump.gain(args) else RawJump.legacy(args)
        RawAccelMode.POWER -> if (args.gain) RawPower.gain(args) else RawPower.legacy(args)
        RawAccelMode.SYNCHRONOUS -> if (args.gain) RawSynchronous.gain(args) else RawSynchronous.legacy(args)
    }

    /**
     * The classic acceleration the curve really has: the field itself for cap modes `input` and `output`, the value derived from
     * the cap point for `in_out` (`accel-classic.hpp:44`, `:99`). Null for the other modes.
     */
    val derivedAcceleration: Double? = when {
        args.mode != RawAccelMode.CLASSIC -> null
        args.capMode != RawAccelCapMode.IN_OUT -> args.acceleration
        args.gain -> classicGainAccel(args.capX, abs(args.capY - 1), args.exponentClassic, args.inputOffset)
        else -> classicBaseAccel(args.capX, abs(args.capY - 1), args)
    }

    /** Sensitivity multiplier at [speed] inches per second as the driver's accel function returns it. */
    override fun multiplier(speed: Double): Double = evaluate(speed)

    override fun toString(): String = "RawAccelCurve(${args.mode.json}, ${if (args.gain) "gain" else "velocity"})"

    private companion object {
        // accel-classic.hpp:12-15 (base_fn) and :17-21 (base_accel)
        fun classicBaseFn(x: Double, accelRaised: Double, args: RawAccelArgs) =
            accelRaised * (x - args.inputOffset).pow(args.exponentClassic) / x

        fun classicBaseAccel(x: Double, y: Double, args: RawAccelArgs): Double {
            val power = args.exponentClassic
            return (x * y * (x - args.inputOffset).pow(-power)).pow(1 / (power - 1))
        }

        // accel-classic.hpp:155-168
        fun classicGainValue(x: Double, accel: Double, power: Double, offset: Double) = power * (accel * (x - offset)).pow(power - 1)
        fun classicGainInverse(y: Double, accel: Double, power: Double, offset: Double) = (accel * offset + (y / power).pow(1 / (power - 1))) / accel
        fun classicGainAccel(x: Double, y: Double, power: Double, offset: Double) = -(y / power).pow(1 / (power - 1)) / (offset - x)

        // accel-classic.hpp:27-77
        fun classicVelocity(args: RawAccelArgs): (Double) -> Double {
            val p = args.exponentClassic
            var cap = Double.MAX_VALUE
            var sign = 1.0
            val accelRaised: Double
            when (args.capMode) {
                RawAccelCapMode.IN_OUT -> {
                    cap = args.capY - 1
                    if (cap < 0) {
                        cap = -cap
                        sign = -sign
                    }
                    accelRaised = classicBaseAccel(args.capX, cap, args).pow(p - 1)
                }
                RawAccelCapMode.INPUT -> {
                    accelRaised = args.acceleration.pow(p - 1)
                    if (args.capX > 0) cap = classicBaseFn(args.capX, accelRaised, args)
                }
                RawAccelCapMode.OUTPUT -> {
                    accelRaised = args.acceleration.pow(p - 1)
                    if (args.capY > 0) {
                        cap = args.capY - 1
                        if (cap < 0) {
                            cap = -cap
                            sign = -sign
                        }
                    }
                }
            }
            return { x -> if (x <= args.inputOffset) 1.0 else sign * minsd(classicBaseFn(x, accelRaised, args), cap) + 1 }
        }

        // accel-classic.hpp:79-153
        fun classicGain(args: RawAccelArgs): (Double) -> Double {
            val p = args.exponentClassic
            var capX = Double.MAX_VALUE
            var capY = Double.MAX_VALUE
            var constant = 0.0
            var sign = 1.0
            val accelRaised: Double
            when (args.capMode) {
                RawAccelCapMode.IN_OUT -> {
                    capX = args.capX
                    capY = args.capY - 1
                    if (capY < 0) {
                        capY = -capY
                        sign = -sign
                    }
                    accelRaised = classicGainAccel(capX, capY, p, args.inputOffset).pow(p - 1)
                    constant = (classicBaseFn(capX, accelRaised, args) - capY) * capX
                }
                RawAccelCapMode.INPUT -> {
                    accelRaised = args.acceleration.pow(p - 1)
                    if (args.capX > 0) {
                        capX = args.capX
                        capY = classicGainValue(capX, args.acceleration, p, args.inputOffset)
                        constant = (classicBaseFn(capX, accelRaised, args) - capY) * capX
                    }
                }
                RawAccelCapMode.OUTPUT -> {
                    accelRaised = args.acceleration.pow(p - 1)
                    if (args.capY > 0) {
                        capY = args.capY - 1
                        if (capY == 0.0) {
                            capX = 0.0
                        } else {
                            if (capY < 0) {
                                capY = -capY
                                sign = -sign
                            }
                            capX = classicGainInverse(capY, args.acceleration, p, args.inputOffset)
                            constant = (classicBaseFn(capX, accelRaised, args) - capY) * capX
                        }
                    }
                }
            }
            return { x ->
                if (x <= args.inputOffset) {
                    1.0
                } else {
                    val output = if (x < capX) classicBaseFn(x, accelRaised, args) else constant / x + capY
                    sign * output + 1
                }
            }
        }

        // accel-natural.hpp:13-18 (natural_base), :25-37
        fun naturalVelocity(args: RawAccelArgs): (Double) -> Double {
            val offset = args.inputOffset
            val limit = args.limit - 1
            val accel = args.decayRate / abs(limit)
            return { x ->
                if (x <= offset) {
                    1.0
                } else {
                    val offsetX = offset - x
                    val decay = exp(accel * offsetX)
                    limit * (1 - (offset - decay * offsetX) / x) + 1
                }
            }
        }

        // accel-natural.hpp:40-57
        fun naturalGain(args: RawAccelArgs): (Double) -> Double {
            val offset = args.inputOffset
            val limit = args.limit - 1
            val accel = args.decayRate / abs(limit)
            val constant = -limit / accel
            return { x ->
                if (x <= offset) {
                    1.0
                } else {
                    val offsetX = offset - x
                    val decay = exp(accel * offsetX)
                    (limit * (decay / accel - offsetX) + constant) / x + 1
                }
            }
        }

        // accel-lookup.hpp:55-97; `velocity` there is args.gain
        fun lookup(args: RawAccelArgs): (Double) -> Double {
            val data = args.lut
            val size = data.size / 2
            val outputSpeed = args.gain
            fun px(i: Int) = data[2 * i].toDouble()
            fun py(i: Int) = data[2 * i + 1].toDouble()
            return fun(x: Double): Double {
                if (x <= 0) return 0.0
                var lo = 0
                var hi = size - 2
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    if (x < px(mid)) {
                        hi = mid - 1
                    } else if (x > px(mid)) {
                        lo = mid + 1
                    } else {
                        return if (outputSpeed) py(mid) / x else py(mid)
                    }
                }
                if (lo > 0) {
                    val y = rawLerp(py(lo - 1), py(lo), (x - px(lo - 1)) / (px(lo) - px(lo - 1)))
                    return if (outputSpeed) y / x else y
                }
                return if (outputSpeed) py(0) / px(0) else py(0)
            }
        }
    }
}

